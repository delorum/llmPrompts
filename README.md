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
deepseek.conversations.directory=~/Downloads
deepseek.conversations.file=~/Downloads/deepseek-conversations.json
deepseek.conversations.archive=~/Downloads/deepseek-data.zip
output.directory=~/llm-prompt-dumps
timezone.offset.hours=3
```

Supported properties and their environment fallbacks:

| Property | Environment variable | Description |
| --- | --- | --- |
| `codex.sessions.directory` | `LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY` | Root directory containing Codex JSONL sessions (optional) |
| `deepseek.conversations.directory` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_DIRECTORY` | Directory containing `deepseek_data-YYYY-MM-DD.zip` archives (optional, highest DeepSeek priority) |
| `deepseek.conversations.file` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE` | DeepSeek conversations JSON file (optional) |
| `deepseek.conversations.archive` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE` | DeepSeek ZIP archive containing `conversations.json` (optional, takes precedence over the JSON file) |
| `output.directory` | `LLM_PROMPTS_OUTPUT_DIRECTORY` | Directory where exports are written |
| `timezone.offset.hours` | `LLM_PROMPTS_TIMEZONE_OFFSET_HOURS` | Output timezone as integer hours from GMT, from `-18` to `+18` (optional, defaults to Moscow at `+3`) |

Values from `config.properties` take precedence. The fallback is applied separately to each setting, so the file and environment variables can be combined. Paths beginning with `~/` are expanded to the current user's home directory.

All exported timestamps are converted to the configured fixed GMT offset. If `timezone.offset.hours` is omitted, Moscow time (`GMT+3`) is used. This is a fixed offset and does not apply daylight-saving rules.

Alternatively, configure settings only through the environment:

```bash
export LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY="$HOME/.codex/sessions"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_DIRECTORY="$HOME/Downloads"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE="$HOME/Downloads/deepseek-conversations.json"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE="$HOME/Downloads/deepseek-data.zip"
export LLM_PROMPTS_OUTPUT_DIRECTORY="$HOME/llm-prompt-dumps"
export LLM_PROMPTS_TIMEZONE_OFFSET_HOURS="+3"
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

Codex export is cumulative. Newly discovered prompts are merged with previously exported prompts and deduplicated; prompts already stored in the export are retained even when their source JSONL session is later removed. A hidden `codex/.prompts-state.json` file stores the structured append state. Do not delete it unless you intend to rebuild the Codex history from the currently available sessions. Existing installations without this state file are migrated once from `codex/all-prompts.txt`. The per-working-directory files and `codex/all-prompts.txt` are regenerated chronologically from the accumulated state.

### DeepSeek

DeepSeek data can be supplied as a directory through `deepseek.conversations.directory`, a ZIP archive through `deepseek.conversations.archive`, or a JSON file through `deepseek.conversations.file`. The priority is directory, then archive, then JSON file.

In directory mode, all regular files matching `deepseek_data-YYYY-MM-DD.zip` are read. Each archive must contain exactly one `conversations.json`. Prompts repeated across archives are deduplicated by conversation ID, timestamp, and text, so a newly downloaded full-history archive can simply be placed alongside older archives.

An unreadable archive, a missing `conversations.json`, invalid JSON, or an unsupported root format is reported to stderr and that archive is skipped. For partially valid JSON, usable REQUEST fragments are exported while malformed conversations, messages, or fragments are skipped and reported with grouped warning counts. Processing then continues with the remaining archives.

The exporter reads `message.fragments[].content` from fragments whose type is `REQUEST`. Prompts are ordered by `message.inserted_at` and written to a single file:

```text
<output-directory>/deepseek/all-prompts.txt
```

DeepSeek export is cumulative. Newly discovered prompts are merged with previously exported prompts and deduplicated; prompts already stored in the export are retained even when their source archive is later removed. A hidden `deepseek/.prompts-state.json` file stores the structured append state. Do not delete it unless you intend to rebuild the DeepSeek history from the currently available inputs. Existing installations without this state file are migrated once from `deepseek/all-prompts.txt`.

The DeepSeek settings are optional. If all of them are absent, DeepSeek is skipped. If neither Codex nor DeepSeek is configured, the application exits successfully without exporting anything. `output.directory` remains required.

### Combined export

When at least one source is configured, the application also creates:

```text
<output-directory>/all-prompts.txt
```

This file combines the cumulative Codex and DeepSeek histories in one chronological stream. Every prompt includes its `LLM` type (`codex` or `deepseek`), session ID, timestamp, text, and the working directory when available for that source.

## Tests

```bash
sbt test
```
