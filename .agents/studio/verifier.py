#!/usr/bin/env python3
"""Independent clean-worktree preparation for studio verification.

The verifier deliberately refuses a dirty source repository.  It records the
committed tree and control-plane digests, creates a detached Git worktree in a
trusted temporary directory, and re-computes those digests before yielding the
checkout to a caller.

This is source isolation, not an execution sandbox.  Commands executed in the
checkout still need an enforcing execution-policy backend.
"""
from __future__ import annotations

import contextlib
import hashlib
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional, Sequence


CONTROL_PATHSPEC = ".agents"
GIT_TIMEOUT_SECONDS = 30.0


class VerificationError(RuntimeError):
    """The requested clean-clone verification cannot be trusted."""


@dataclass(frozen=True)
class SourceSnapshot:
    commit: str
    tree_digest: str
    control_digest: str

    def as_dict(self) -> dict[str, str]:
        return {
            "commit": self.commit,
            "tree_digest": self.tree_digest,
            "control_digest": self.control_digest,
        }


@dataclass(frozen=True)
class VerifiedWorktree:
    source_repository: Path
    checkout: Path
    source: SourceSnapshot
    checkout_snapshot: SourceSnapshot

    def attestation(self) -> dict[str, object]:
        return {
            "schema_version": 1,
            "stability": "provisional",
            "state": "verified",
            "isolation": "detached_clean_git_worktree",
            "execution_sandbox": False,
            "source_repository": str(self.source_repository),
            "checkout": str(self.checkout),
            "source": self.source.as_dict(),
            "checkout_snapshot": self.checkout_snapshot.as_dict(),
        }


def _git_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "LC_ALL": "C",
        }
    )
    return environment


def _run_git(
    repository: Path,
    arguments: Sequence[str],
    *,
    timeout_seconds: float = GIT_TIMEOUT_SECONDS,
    binary: bool = False,
) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            [
                "git",
                "-c",
                "core.hooksPath=",
                "-c",
                "core.fsmonitor=false",
                "-C",
                str(repository),
                *arguments,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=not binary,
            encoding=None if binary else "utf-8",
            errors=None if binary else "replace",
            env=_git_environment(),
            check=False,
            timeout=timeout_seconds,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(f"git command failed to run: {error}") from error


def _git_stdout(
    repository: Path,
    arguments: Sequence[str],
    *,
    binary: bool = False,
) -> str | bytes:
    result = _run_git(repository, arguments, binary=binary)
    if result.returncode != 0:
        error_text = (
            result.stderr.decode("utf-8", errors="replace")
            if isinstance(result.stderr, bytes)
            else result.stderr
        ).strip()
        raise VerificationError(
            "git command failed"
            f" ({' '.join(arguments)}): {error_text or result.returncode}"
        )
    return result.stdout


def repository_root(path: Path | str) -> Path:
    candidate = Path(path).resolve()
    output = _git_stdout(candidate, ["rev-parse", "--show-toplevel"])
    root = Path(str(output).strip()).resolve()
    if root != candidate:
        raise VerificationError(
            f"verification must target the repository root: {root}"
        )
    return root


def require_clean_repository(repository: Path | str) -> None:
    root = repository_root(repository)
    status = _git_stdout(
        root,
        [
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ],
        binary=True,
    )
    assert isinstance(status, bytes)
    if status:
        entries = [
            entry.decode("utf-8", errors="replace")
            for entry in status.split(b"\0")
            if entry
        ]
        preview = ", ".join(entries[:5])
        if len(entries) > 5:
            preview += f", … ({len(entries)} entries)"
        raise VerificationError(
            "source repository is dirty; commit or remove every change before "
            f"independent verification: {preview}"
        )


def _tree_listing(
    repository: Path,
    commit: str,
    pathspecs: Sequence[str] = (),
) -> bytes:
    arguments = ["ls-tree", "-r", "-z", "--full-tree", commit]
    if pathspecs:
        arguments.extend(["--", *pathspecs])
    output = _git_stdout(repository, arguments, binary=True)
    assert isinstance(output, bytes)
    return output


def git_tree_digest(
    repository: Path | str,
    commit: str,
    pathspecs: Sequence[str] = (),
    *,
    require_entries: bool = True,
) -> str:
    """Hash Git's committed mode/type/blob/path records with SHA-256."""
    root = repository_root(repository)
    listing = _tree_listing(root, commit, pathspecs)
    if require_entries and not listing:
        label = ", ".join(pathspecs) if pathspecs else "<entire tree>"
        raise VerificationError(f"committed pathspec has no entries: {label}")
    digest = hashlib.sha256()
    digest.update(b"afs-git-tree-digest-v1\0")
    for pathspec in pathspecs:
        digest.update(pathspec.encode("utf-8"))
        digest.update(b"\0")
    digest.update(listing)
    return digest.hexdigest()


def snapshot_repository(repository: Path | str) -> SourceSnapshot:
    root = repository_root(repository)
    commit_output = _git_stdout(root, ["rev-parse", "--verify", "HEAD^{commit}"])
    commit = str(commit_output).strip()
    return SourceSnapshot(
        commit=commit,
        tree_digest=git_tree_digest(root, commit),
        control_digest=git_tree_digest(
            root,
            commit,
            (CONTROL_PATHSPEC,),
        ),
    )


def _safe_temp_parent(path: Path | str | None) -> Path | None:
    if path is None:
        return None
    parent = Path(path).resolve()
    parent.mkdir(parents=True, exist_ok=True)
    if not parent.is_dir():
        raise VerificationError(f"temporary parent is not a directory: {parent}")
    return parent


@contextlib.contextmanager
def prepare_clean_worktree(
    repository: Path | str,
    *,
    expected_commit: Optional[str] = None,
    expected_control_digest: Optional[str] = None,
    temp_parent: Path | str | None = None,
) -> Iterator[VerifiedWorktree]:
    """Yield a digest-checked detached checkout and remove only that checkout."""
    root = repository_root(repository)
    require_clean_repository(root)
    source = snapshot_repository(root)
    if expected_commit is not None and source.commit != expected_commit:
        raise VerificationError(
            f"commit mismatch: expected {expected_commit}, got {source.commit}"
        )
    if (
        expected_control_digest is not None
        and source.control_digest != expected_control_digest
    ):
        raise VerificationError(
            "control digest mismatch before clean checkout: expected "
            f"{expected_control_digest}, got {source.control_digest}"
        )

    parent = _safe_temp_parent(temp_parent)
    temporary_root = Path(
        tempfile.mkdtemp(prefix="afs-verifier-", dir=parent)
    ).resolve()
    checkout = temporary_root / "checkout"
    registered = False
    try:
        result = _run_git(
            root,
            [
                "worktree",
                "add",
                "--detach",
                str(checkout),
                source.commit,
            ],
        )
        if result.returncode != 0:
            raise VerificationError(
                "could not create detached verifier worktree: "
                + str(result.stderr).strip()
            )
        registered = True
        checkout_root = repository_root(checkout)
        require_clean_repository(checkout_root)
        checkout_snapshot = snapshot_repository(checkout_root)
        if checkout_snapshot != source:
            raise VerificationError(
                "clean checkout digest mismatch; verifier source changed or "
                "the checkout is not faithful"
            )
        yield VerifiedWorktree(
            source_repository=root,
            checkout=checkout_root,
            source=source,
            checkout_snapshot=checkout_snapshot,
        )
    finally:
        if registered:
            remove_result = _run_git(
                root,
                ["worktree", "remove", "--force", str(checkout)],
            )
            if remove_result.returncode != 0 and checkout.exists():
                raise VerificationError(
                    "could not remove verifier-owned worktree: "
                    + str(remove_result.stderr).strip()
                )
        # The directory was created by this function and its resolved absolute
        # path is known.  Never broaden cleanup to the caller's temp parent.
        shutil.rmtree(temporary_root, ignore_errors=True)
