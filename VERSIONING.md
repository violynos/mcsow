# Versioning & Release Workflow

Version format: **`1.MAJOR.MINOR(letter)`** — set in `gradle.properties` as `mod_version`
(e.g. `1.9.6` or `1.9.6a`). The leading `1` is **frozen** and never changes.

The version tier is chosen by the **word used when shipping**, not by the size of the change:

| Trigger | Version change | Example | Meaning |
|---|---|---|---|
| *(no "bump" — just a fix/approval)* | append/increment the trailing **letter** | `1.9.6` → `1.9.6a` → `1.9.6b` | **hotfix** |
| **"bump"** | increment the **MINOR** (last) number, drop the letter | `1.9.6a` → `1.9.7` | **minor release** |
| **"major bump"** (or a big/breaking milestone) | increment the **MAJOR** (2nd) number, reset MINOR to 0, drop the letter | `1.9.x` → `1.10.0` | **major release** |

Bump **once per batch** of changes, not per file.

## Ship steps

Shipping is triggered by an explicit command (e.g. "commit push merge release"); the
"bump"/"major bump"/nothing word only selects the numeric-vs-letter scheme above.

1. Set `mod_version` in `gradle.properties` per the table.
2. Build + deploy: `./buildvio.sh` — copies the jar to the PrismLauncher **Mod Testing**
   instance *and* `~/testserver/mods`.
3. During iteration, **hold** the commit/release until confirmed in-game (movement/HUD
   changes are tested by hand; a headless job can't launch MC).
4. On the ship command:
   - `git commit` + `git push origin <branch>`
   - `gh release create vX.Y.Z(l) --repo violynos/mcsow --target <branch> --title "..." --notes "..." build/libs/mcsow-X.Y.Z(l).jar`
   - Fast-forward master: `git push origin HEAD:master` (only if it's a clean fast-forward).

Repo: `violynos/mcsow` (remote `origin`, HTTPS via the `gh` credential helper).
