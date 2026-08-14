# LLM Prompt Dumper

[Русская версия](README.ru.md)

A small Scala CLI application that exports user prompts from Codex and DeepSeek conversations.

The application reads Codex JSONL session files, such as those stored under `~/.codex/sessions`, and excludes model responses, service context, duplicate records, and internal subagent sessions.

## Requirements

- JDK 17 or newer
- sbt

## Usage

The application reads its settings from:

- `~/.config/llm-prompts/config.properties`;
- environment variables as a fallback for settings missing from the properties file.

If `XDG_CONFIG_HOME` is set and non-empty, the configuration file is read from `$XDG_CONFIG_HOME/llm-prompts/config.properties` instead. Otherwise, the application uses `~/.config/llm-prompts/config.properties`.

Create the configuration directory and file:

```bash
mkdir -p ~/.config/llm-prompts
```

```properties
# ~/.config/llm-prompts/config.properties
codex.sessions.directory=~/.codex/sessions
deepseek.conversations.file=~/Downloads/deepseek-conversations.json
deepseek.conversations.archive=~/Downloads/deepseek-data.zip
output.directory=~/llm-prompt-dumps
```

Supported properties and their environment fallbacks:

| Property | Environment variable | Description |
| --- | --- | --- |
| `codex.sessions.directory` | `LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY` | Root directory containing Codex JSONL sessions (optional) |
| `deepseek.conversations.file` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE` | DeepSeek conversations JSON file (optional) |
| `deepseek.conversations.archive` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE` | DeepSeek ZIP archive containing `conversations.json` (optional, takes precedence over the JSON file) |
| `output.directory` | `LLM_PROMPTS_OUTPUT_DIRECTORY` | Directory where exports are written |

Values from `config.properties` take precedence. The fallback is applied separately to each setting, so the file and environment variables can be combined. Paths beginning with `~/` are expanded to the current user's home directory.

Alternatively, configure both settings only through the environment:

```bash
export LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY="$HOME/.codex/sessions"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE="$HOME/Downloads/deepseek-conversations.json"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE="$HOME/Downloads/deepseek-data.zip"
export LLM_PROMPTS_OUTPUT_DIRECTORY="$HOME/llm-prompt-dumps"
```

Run the application without command-line arguments from the project directory:

```bash
sbt run
```

## Output format

Codex exports are written below `<output-directory>/codex`, leaving the output root available for other LLM exporters. The exporter creates one UTF-8 `prompts.txt` file for each working directory found in the sessions. The working directory path is reproduced below the Codex output directory.

Codex is exported only when `codex.sessions.directory` or its environment fallback is configured.

For example, sessions whose working directory is `/home/user/project` are exported to:

```text
<output-directory>/codex/home/user/project/prompts.txt
```

Each file contains:

- session IDs and the number of prompts in each session;
- the original working directory;
- all user prompts ordered chronologically;
- the session ID before every prompt;
- the ISO 8601 timestamp of every prompt.

The exporter also creates `<output-directory>/codex/all-prompts.txt`. This file combines prompts from every working directory in a single chronological stream. It uses the same format, with the working directory additionally written before every prompt.

Existing `prompts.txt` files and `all-prompts.txt` are replaced completely during a run. Prompts are not appended or duplicated. The exporter does not remove stale files for working directories that are no longer present in the input sessions.

### DeepSeek

DeepSeek data can be supplied either as a JSON file through `deepseek.conversations.file` or as a ZIP archive through `deepseek.conversations.archive`. The archive must contain exactly one `conversations.json`. When both settings are present, the archive takes precedence.

The exporter reads `message.fragments[].content` from fragments whose type is `REQUEST`. Prompts are ordered by `message.inserted_at` and written to a single file:

```text
<output-directory>/deepseek/all-prompts.txt
```

The DeepSeek settings are optional. If both are absent, DeepSeek is skipped. If neither Codex nor DeepSeek is configured, the application exits successfully without exporting anything. `output.directory` remains required.

## Tests

```bash
sbt test
```
