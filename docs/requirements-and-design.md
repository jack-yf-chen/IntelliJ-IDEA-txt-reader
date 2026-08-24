# IntelliJ IDEA Novel Reader Plugin Requirements and Design

Date: 2026-08-24

## Background

The user is using IntelliJ IDEA 2026.1.3. Existing public marketplace novel reader plugins are not usable because of compatibility issues. This project will provide a dedicated IntelliJ Platform plugin that supports this IDE version.

JetBrains official compatibility notes identify IntelliJ Platform 2026.1 as branch `261`, with Java 21 as the required runtime level. The implementation therefore targets IntelliJ Platform `2026.1.3`, uses `sinceBuild = "261"`, and keeps `untilBuild` open for later compatibility unless verification shows a reason to restrict it.

References checked on 2026-08-24:

- IntelliJ Platform build number ranges: https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
- IntelliJ Platform Gradle Plugin 2.x: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- IntelliJ Platform Gradle Plugin extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html

## Version 1 Scope

Version 1 focuses on TXT reading only.

Required features:

- Installable IntelliJ Platform plugin for IntelliJ IDEA 2026.1.3.
- Open plugin from the `Tools` menu.
- Provide a `Novel Reader` tool window, initially anchored on the right side.
- Open local `.txt` files.
- Display Chinese TXT content correctly where possible.
- Detect chapters from common TXT headings.
- Navigate previous and next chapters.
- Jump to a chapter from a chapter selector.
- Increase and decrease reading font size.
- Save and restore basic reading state.

Out of scope for Version 1:

- EPUB, PDF, MOBI, or online book sources.
- Bookshelf management.
- Cloud sync.
- Marketplace publishing automation.
- Complex themes and typography presets.

## UX Design

The plugin surface is a tool window named `Novel Reader`.

Toolbar controls:

- Open TXT file.
- Previous chapter.
- Chapter selector.
- Next chapter.
- Decrease font size.
- Increase font size.

Main reading area:

- Scrollable plain text reading view.
- Line wrap enabled.
- Default font size suitable for reading inside an IDE panel.

Status area:

- Current file name.
- Current chapter number and total chapter count.
- Current encoding.

## Technical Design

Language and build:

- Kotlin
- Gradle Kotlin DSL
- IntelliJ Platform Gradle Plugin 2.x
- Java toolchain 21

Main components:

- `OpenNovelAction`: action registered under `ToolsMenu`; opens a TXT file chooser and activates the tool window.
- `NovelReaderToolWindowFactory`: creates the tool window and installs the reader panel.
- `ReaderPanel`: Swing UI for reading, navigation, font controls, and state persistence.
- `TxtBookLoader`: TXT loader with charset fallback.
- `ChapterParser`: simple chapter parser based on common Chinese and English chapter headings.
- `ReaderStateService`: project-level persistent state for last file, charset, chapter index, scroll position, and font size.

TXT encoding strategy:

1. Try UTF-8.
2. Try GB18030.
3. Try GBK.
4. Report a readable error if all attempts fail.

Chapter parsing strategy:

- Match common chapter lines such as:
  - `第1章`
  - `第一章`
  - `第001回`
  - `Chapter 1`
- If no chapters are found, treat the whole file as one chapter.

## Validation Plan

Minimum local validation:

- Compile Kotlin sources.
- Run Gradle plugin structure verification if dependencies are available.
- Build plugin ZIP with `buildPlugin`.

Manual validation in IntelliJ IDEA 2026.1.3:

- Install generated ZIP plugin.
- Confirm `Tools -> Novel Reader` exists.
- Open a UTF-8 TXT file.
- Open a GBK/GB18030 Chinese TXT file.
- Confirm text rendering, chapter navigation, font adjustment, and persisted state.

## Development Log

- 2026-08-24: Requirements and design document created before implementation.
- 2026-08-24: Project scaffold created with Kotlin, Gradle Kotlin DSL, IntelliJ Platform Gradle Plugin `2.18.1`, and IntelliJ IDEA `2026.1.3` as the target platform.
- 2026-08-24: Implemented TXT loading with UTF-8, GB18030, and GBK fallback.
- 2026-08-24: Implemented simple chapter parsing for common Chinese headings and `Chapter N` headings.
- 2026-08-24: Implemented `Tools -> Novel Reader` action and right-anchored `Novel Reader` tool window.
- 2026-08-24: Implemented reader panel with file opening, chapter selector, previous/next chapter controls, font size controls, and project-level persisted reading state.
- 2026-08-24: Local build could not be completed because `gradle` and `kotlinc` are not installed or not available on `PATH`; detailed validation notes are recorded in `docs/development-record.md`.
