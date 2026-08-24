# Development Record

Date: 2026-08-24

## Implemented

- Created IntelliJ Platform plugin project scaffold.
- Added Gradle Kotlin DSL configuration targeting IntelliJ IDEA `2026.1.3`.
- Set Java/Kotlin toolchain target to Java 21.
- Set plugin compatibility to IntelliJ Platform branch `261`.
- Added plugin metadata and IntelliJ registrations in `META-INF/plugin.xml`.
- Added `Tools -> Novel Reader` action.
- Added right-anchored `Novel Reader` tool window.
- Added TXT loader with UTF-8, GB18030, and GBK fallback.
- Added chapter parser for common Chinese chapter headings and `Chapter N`.
- Added Swing reader panel with:
  - Open TXT
  - Previous chapter
  - Chapter selector
  - Next chapter
  - Decrease/increase font size
  - Status display
- Added project-level persisted reading state:
  - Last file path
  - Charset
  - Chapter index
  - Scroll position
  - Font size
- Added `.gitignore` and project `README.md`.

## Files Added

- `.gitignore`
- `README.md`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `docs/requirements-and-design.md`
- `docs/development-record.md`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/resources/icons/reader.svg`
- `src/main/kotlin/com/chen/reader/ChapterParser.kt`
- `src/main/kotlin/com/chen/reader/NovelReaderOpener.kt`
- `src/main/kotlin/com/chen/reader/NovelReaderToolWindowFactory.kt`
- `src/main/kotlin/com/chen/reader/OpenNovelAction.kt`
- `src/main/kotlin/com/chen/reader/ReaderPanel.kt`
- `src/main/kotlin/com/chen/reader/ReaderStateService.kt`
- `src/main/kotlin/com/chen/reader/TxtBookLoader.kt`
- `src/main/kotlin/com/chen/reader/model/Book.kt`

## Validation Performed

- Checked repository state before edits: the repository was empty except for `.git`.
- Checked generated file list with `rg --files`.
- Checked for leftover markers or stale configuration values with `rg`.
- Checked that `plugin.xml` contains the expected action and tool window registration.
- Checked local build tool availability:
  - `gradle --version` failed because `gradle` is not installed or not on `PATH`.
  - `kotlinc -version` failed because `kotlinc` is not installed or not on `PATH`.
  - `GRADLE_HOME` is not set.
  - No reusable local IntelliJ Platform Gradle Plugin cache was found under the current user Gradle cache.

## Validation Not Completed

- Gradle compilation was not run.
- `buildPlugin` was not run.
- Plugin ZIP was not generated.
- Manual installation into IntelliJ IDEA 2026.1.3 was not performed.

## Fixes

- 2026-08-24: Fixed Gradle settings configuration after project import failed with unresolved `intellijPlatform` and `defaultRepositories` in `settings.gradle.kts`. Added the official `org.jetbrains.intellij.platform.settings` plugin and moved IntelliJ Platform plugin version declaration to settings only.
- 2026-08-24: Removed obsolete IntelliJ Platform dependency helpers after Gradle failed on unresolved `instrumentationTools()`. IntelliJ Platform Gradle Plugin 2.18.1 no longer requires that helper for code instrumentation.
- 2026-08-24: Fixed Kotlin compilation failure caused by unavailable `UIUtil.getTextAreaBackground()` on the target IntelliJ Platform. Replaced it with Swing `UIManager.getColor("TextArea.background")`.
- 2026-08-24: Verified `buildPlugin` with IDEA 2026.1.3 bundled JBR as Gradle runtime. Build completed successfully and generated a plugin ZIP under `build/distributions/`.
- 2026-08-24: Started `runIde` successfully. The sandbox IntelliJ IDEA 2026.1.3 Welcome window opened, the project trust dialog was reached, and the Microsoft Defender exclusion checkbox was cleared before trusting. Further UI verification was stopped when the user pressed Escape to stop Computer Use.
- 2026-08-24: Fixed toolbar clipping when the Novel Reader tool window is narrow. The action buttons now stay on the first row and the chapter selector fills a second row, preventing the chapter selector and later buttons from being hidden.
- 2026-08-24: Added reading comfort controls. The reader now uses `JTextPane` with paragraph styling, keeps font size controls, adds line spacing controls, persists line spacing, and shows current font size and line spacing in the status bar.
- 2026-08-24: Added continuous chapter boundary navigation and font family selection. Scrolling past the end of a chapter now advances to the next chapter; scrolling upward at the start moves to the previous chapter near its end. The reader now lists installed system fonts with common Chinese fonts prioritized, applies the selected font immediately, and persists the font family.

## Next Validation Steps

After Gradle is available, run:

```powershell
gradle buildPlugin
```

Then install the ZIP from:

```text
build/distributions/
```

Manual smoke test:

- Confirm `Tools -> Novel Reader` is visible.
- Open a UTF-8 TXT file.
- Open a GBK or GB18030 Chinese TXT file.
- Confirm chapter detection and navigation.
- Confirm font size controls.
- Restart IntelliJ IDEA and confirm reading state restores.
