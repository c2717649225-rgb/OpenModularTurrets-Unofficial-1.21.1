#!/usr/bin/env python3
"""Regression tests for verified NeoForge truth rules."""
from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path

GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
PROJECT_ROOT = AGENTS_DIR.parent
EVAL_DIR = AGENTS_DIR / "eval"

sys.path.insert(0, str(GATES_DIR))
sys.path.insert(0, str(EVAL_DIR))

import grade
import static_gate


class TestPayloadThreadTruth(unittest.TestCase):
    def scan(self, source: str, filename: str = "PayloadHandlers.java"):
        java_root = PROJECT_ROOT / "src" / "main" / "java"
        return static_gate.scan_file(
            java_root / "example" / filename,
            source,
            java_root=java_root,
            mod_id="example",
        )

    @staticmethod
    def payload_source(
        *,
        registrar_setup: str,
        handler_body: str,
        registrar_name: str = "registrar",
    ) -> str:
        return f"""
            public final class PayloadHandlers {{
                public static void register(RegisterPayloadHandlersEvent event) {{
                    {registrar_setup}
                    {registrar_name}.playToServer(
                        TestPayload.TYPE,
                        TestPayload.STREAM_CODEC,
                        PayloadHandlers::handle
                    );
                }}

                public static void handle(
                        TestPayload payload,
                        IPayloadContext context
                ) {{
                    {handler_body}
                }}
            }}
        """

    def test_default_main_handler_does_not_require_enqueue_work(self):
        source = self.payload_source(
            registrar_setup='var registrar = event.registrar("1");',
            handler_body="context.player().heal(1.0F);",
        )

        findings = self.scan(source)

        self.assertNotIn(
            "payload_thread_safety",
            {finding.rule_id for finding in findings},
        )

    def test_ignored_executes_on_return_does_not_change_registrar_mode(self):
        source = self.payload_source(
            registrar_setup=(
                'var registrar = event.registrar("1"); '
                "registrar.executesOn(HandlerThread.NETWORK);"
            ),
            handler_body="context.player().heal(1.0F);",
        )

        findings = self.scan(source)

        self.assertNotIn(
            "payload_thread_safety",
            {finding.rule_id for finding in findings},
        )

    def test_explicit_network_state_write_requires_enqueue_work(self):
        source = self.payload_source(
            registrar_setup=(
                'var registrar = event.registrar("1")'
                ".executesOn(HandlerThread.NETWORK);"
            ),
            handler_body="context.player().heal(1.0F);",
        )

        findings = self.scan(source)
        thread_findings = [
            finding
            for finding in findings
            if finding.rule_id == "payload_thread_safety"
        ]

        self.assertEqual(1, len(thread_findings))
        self.assertIn("HandlerThread.NETWORK", thread_findings[0].message)

    def test_explicit_network_enqueue_work_is_accepted(self):
        source = self.payload_source(
            registrar_setup=(
                'var registrar = event.registrar("1")'
                ".executesOn(HandlerThread.NETWORK);"
            ),
            handler_body=(
                "context.enqueueWork(() -> "
                "context.player().heal(1.0F)).exceptionally(error -> null);"
            ),
        )

        findings = self.scan(source)

        self.assertNotIn(
            "payload_thread_safety",
            {finding.rule_id for finding in findings},
        )

    def test_t03_grader_is_conditional_on_explicit_network_mutation(self):
        default_corpus = """
            var registrar = event.registrar("1");
            context.player().heal(1.0F);
        """
        network_corpus = """
            var registrar = event.registrar("1")
                .executesOn(HandlerThread.NETWORK);
            context.player().heal(1.0F);
        """
        safe_network_corpus = network_corpus + """
            context.enqueueWork(() -> context.player().heal(1.0F))
                .exceptionally(error -> null);
        """

        self.assertEqual("PASS", grade.assess_t03_threading(default_corpus)[0])
        self.assertEqual("PARTIAL", grade.assess_t03_threading(network_corpus)[0])
        self.assertEqual(
            "PASS",
            grade.assess_t03_threading(safe_network_corpus)[0],
        )

    def test_t03_full_grade_accepts_default_main_without_enqueue_work(self):
        default_corpus = """
            record TestPayload() implements CustomPacketPayload {
                static final StreamCodec STREAM_CODEC = null;
            }
            void register(PayloadRegistrar registrar) {
                registrar.playToServer(
                    TestPayload.TYPE,
                    TestPayload.STREAM_CODEC,
                    PayloadHandlers::handle
                );
            }
            void handle(TestPayload payload, IPayloadContext context) {
                context.player().heal(1.0F);
            }
        """
        explicit_network_corpus = default_corpus + """
            var networkRegistrar = event.registrar("1")
                .executesOn(HandlerThread.NETWORK);
            networkRegistrar.playToServer(
                TestPayload.TYPE,
                TestPayload.STREAM_CODEC,
                PayloadHandlers::handle
            );
        """

        with contextlib.redirect_stdout(io.StringIO()):
            default_result = grade.grade(
                "T03",
                default_corpus,
                skip_gates=True,
            )
            network_result = grade.grade(
                "T03",
                explicit_network_corpus,
                skip_gates=True,
            )

        self.assertEqual("PASS", default_result)
        self.assertEqual("PARTIAL", network_result)


class TestCodecFactoryMappingTruth(unittest.TestCase):
    def scan(self, source: str):
        java_root = PROJECT_ROOT / "src" / "main" / "java"
        return static_gate.scan_file(
            java_root / "example" / "Sample.java",
            source,
            java_root=java_root,
            mod_id="example",
        )

    def test_direct_record_constructor_mismatch_is_reported(self):
        source = """
            public record Sample(String first, String second) {
                static final Codec<Sample> CODEC =
                    RecordCodecBuilder.create(instance ->
                        instance.group(
                            Codec.STRING.fieldOf("second")
                                .forGetter(Sample::second),
                            Codec.STRING.fieldOf("first")
                                .forGetter(Sample::first)
                        ).apply(instance, Sample::new)
                    );
            }
        """

        findings = [
            finding
            for finding in self.scan(source)
            if finding.rule_id == "codec_field_order"
        ]

        self.assertEqual(1, len(findings))
        self.assertIn("Sample::new", findings[0].message)
        self.assertIn("mapped to the wrong components or rejected", findings[0].message)
        self.assertNotIn("corrupts saves", findings[0].message)

    def test_explicit_adapter_lambda_may_legally_reorder_values(self):
        source = """
            public record Sample(String first, String second) {
                static final Codec<Sample> CODEC =
                    RecordCodecBuilder.create(instance ->
                        instance.group(
                            Codec.STRING.fieldOf("second")
                                .forGetter(Sample::second),
                            Codec.STRING.fieldOf("first")
                                .forGetter(Sample::first)
                        ).apply(
                            instance,
                            (second, first) -> new Sample(first, second)
                        )
                    );
            }
        """

        findings = self.scan(source)

        self.assertNotIn(
            "codec_field_order",
            {finding.rule_id for finding in findings},
        )


class TestVerifiedDocumentationTruth(unittest.TestCase):
    def test_payload_docs_state_default_main_semantics(self):
        paths = [
            AGENTS_DIR / "AGENTS.md",
            AGENTS_DIR / "skills" / "neoforge" / "SKILL.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "network_payloads.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "architecture_design.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "playbooks"
            / "pb_network_payload.md",
            AGENTS_DIR / "eval" / "tasks" / "T03_payload.md",
        ]

        for path in paths:
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertNotRegex(text, r"Handler\s*默认(?:运行)?在网络线程")
                self.assertIn("默认", text)
                self.assertIn("主线程", text)

    def test_architecture_doc_rejects_dangerous_absolutes(self):
        path = (
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "architecture_design.md"
        )
        text = path.read_text(encoding="utf-8")

        self.assertIn("last_verified: 2026-07-27", text)
        self.assertNotIn("Block` 类仅负责方块物理定义", text)
        self.assertNotIn("优先使用 `ConcurrentHashMap`", text)
        self.assertNotIn("所有内部异常必须被捕获", text)
        self.assertIn("不要用宽泛 `catch (Exception)`", text)

    def test_event_bus_docs_preserve_the_21_1_181_boundary(self):
        paths = [
            AGENTS_DIR / "AGENTS.md",
            AGENTS_DIR / "skills" / "neoforge" / "SKILL.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "event_system.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "anti_patterns.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "architecture_design.md",
        ]

        for path in paths:
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertIn("21.1.180", text)
                self.assertIn("21.1.181", text)
                self.assertNotRegex(text, r"一律省略\s*`?bus")

    def test_record_codec_docs_describe_factory_mapping_not_an_absolute(self):
        paths = [
            AGENTS_DIR / "AGENTS.md",
            AGENTS_DIR / "skills" / "neoforge" / "SKILL.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "data_components.md",
            AGENTS_DIR
            / "skills"
            / "neoforge"
            / "references"
            / "anti_patterns.md",
        ]

        for path in paths:
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertIn(".apply(...)", text)
                self.assertIn("lambda", text)
                self.assertNotIn("100% 绝对一致", text)
                self.assertNotIn("直接损坏用户的物理存档", text)


if __name__ == "__main__":
    unittest.main()
