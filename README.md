# LLM Prompt Dumper

[Русская версия](README.ru.md)

A small Scala CLI application that exports user prompts from Codex sessions.

> Currently, only Codex session exports are supported.

The application reads Codex JSONL session files, such as those stored under `~/.codex/sessions`, and excludes model responses, service context, duplicate records, and internal subagent sessions.

## Requirements

- JDK 17 or newer
- sbt

## Usage

From the project directory, run:

```bash
sbt "run <codex-sessions-directory> <output-directory>"
```

For example:

```bash
sbt "run ~/.codex/sessions ~/llm-prompt-dumps"
```

The command takes exactly two arguments:

1. The root directory containing Codex JSONL sessions, usually `~/.codex/sessions`.
2. The directory where the exported prompts should be written.

If a path contains spaces, quote it inside the sbt command:

```bash
sbt "run '/path/to/codex sessions' '/path/to/prompt dumps'"
```

## Output format

The exporter creates one UTF-8 `prompts.txt` file for each working directory found in the sessions. The working directory path is reproduced below the selected output directory.

For example, sessions whose working directory is `/home/user/project` are exported to:

```text
<output-directory>/home/user/project/prompts.txt
```

Each file contains:

- session IDs and the number of prompts in each session;
- the original working directory;
- all user prompts ordered chronologically;
- the session ID whenever the chronological stream switches sessions;
- the ISO 8601 timestamp of every prompt.

Existing `prompts.txt` files for working directories found during a run are replaced completely. Prompts are not appended or duplicated. The exporter does not remove stale files for working directories that are no longer present in the input sessions.

## Tests

```bash
sbt test
```
