# Legacy OMT audio provenance ledger

## Status

**Development-use migration complete; redistribution review unresolved.**

The files below were copied byte-for-byte from:

```text
D:/c128/mods/reference-sources/OpenModularTurrets-1.12/
  src/main/resources/assets/openmodularturrets/sounds/
```

The reference checkout reports commit `3625d9d` and contains a GPL-3.0 root
license. That fact alone is not sufficient evidence that every bundled audio
file was authored by the repository contributors or was relicensed with all
required permissions.

Several OGG comment blocks name third-party artists, while the old repository
does not include a per-file source URL, original license, modification record
or attribution ledger. Consequently:

- the files may be used locally to verify the port;
- they must not be described as individually cleared for redistribution;
- release must either recover and satisfy each original license or replace the
  files with newly authored/clearly licensed audio;
- the project-level GPL-3.0-only `LICENSE` and mod metadata do not resolve
  those per-file permissions; OMT-derived assets still require a separate
  provenance decision before publication.

The source repository's GPL text is retained at
`LICENSES/OpenModularTurrets-GPL-3.0.txt`. This is provenance preservation, not
a conclusion that it resolves the unidentified audio sources.

## Event and file scope

The 18 registered legacy events map one-to-one to the files below. The old
`windup.ogg` is excluded because it was never registered and all old call sites
were commented out.

| File | Bytes | SHA-256 |
|---|---:|---|
| amped.ogg | 9030 | `cd37c89aac1aebc8da32df0fcd8a489fb89dc29b291e29f5604e0039a3630945` |
| bullet_hit.ogg | 9185 | `682ea50b7ec6608bb260a722b09e213b4125b8a3a29cf44e56caf07f4f021910` |
| disposable.ogg | 16746 | `b1b590b8242d4fd9194092884108fb3308cd5e03c4e1a4b529fe63786acf7125` |
| grenade.ogg | 8184 | `ed86cf0e8ccd9c95d54fbc91c8fb6b23c02e93860f31c06403cd46b2115d954d` |
| incendiary.ogg | 15629 | `38b6907a204ef3af8da56e33ec4b8a6ee6cc27454fad6ca14ca78b656ce2c1b6` |
| laser.ogg | 7923 | `28754821cceb50a82743884dc0bd7b7771471bbe2a9d7342a6cd50903bc4f7d3` |
| laser_hit.ogg | 13855 | `1f91f6480e789ea3f1dbc143a58df43712a06fbf5dcf2bbd52efc1fc4ea10a6e` |
| machine_gun.ogg | 10244 | `0af9e0c2bd553eaaecef15b8bbe75235c73367d87b2845d3af83b2374be2ff5c` |
| plasma_launch.ogg | 8926 | `c7faf15b3e3d7c80ba8a30ef89d23598dfb81ac65decb1df999a7cab14d61140` |
| potato.ogg | 11840 | `5dafaedf42e99963181b752ed8045d80b3f1071ef4eb64ee9b618ea58c63b33b` |
| rail_gun.ogg | 34824 | `5ff4d47a72f132d211c3557df66cc3a567c21091600212a12d192a3bae646a5a` |
| rail_gun_hit.ogg | 15244 | `89b6057019970752b89fd1992bdb09310578d5a762aa0d54452b98e4ee367418` |
| relativistic.ogg | 16224 | `45305f331d75982ecfe1b0a6bac2b4401e647d1bdd8956bfe77d27b496f8bb85` |
| rocket.ogg | 19344 | `9fc3a960659a66c7ea3d7508a2d2a60e9fac6cf37216f8b2e45540005d133faf` |
| teleport.ogg | 9921 | `f037bd5c794d4a6a4591116838867053c92e250f257b4657560b16d279e316b1` |
| turret_deploy.ogg | 17747 | `195c852c8ca4b614f2652715d78a1a32b897fc0c2a54171a8ecc9f41a9521859` |
| turret_retract.ogg | 19539 | `1dde79f26af74a6845e960ef3a98527b132fc1f897cd61fcda059852e95ac349` |
| warning.ogg | 8800 | `f0b3df9978d11f0448b4fb31d400140b9c525634e0c1fa32b497c517d6f667a4` |

## Embedded metadata requiring follow-up

The following artist strings are present in OGG metadata:

| File | Embedded artist/title |
|---|---|
| `rail_gun.ogg` | Roper — “Ships railgun” |
| `rail_gun_hit.ogg` | FoolBoy Media — “+MessySplat1” |
| `rocket.ogg` | Iwan “qubodup” Gabovitch |
| `turret_deploy.ogg` | Martin Taylor — “Electric Garbage Can” |
| `turret_retract.ogg` | Martin Taylor — “Electric Garbage Can” |
| `warning.ogg` | MrSt3v3n999 |

No original license or canonical source URL was found in the local reference
checkout for these entries. The other files also remain pending; absence of an
artist tag is not proof of ownership.
