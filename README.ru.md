# LLM Prompt Dumper

[English version](README.md)

Небольшое CLI-приложение на Scala, которое выгружает пользовательские запросы из сессий Codex и бесед DeepSeek.

Программа читает JSONL-файлы сессий Codex, обычно расположенные в `~/.codex/sessions`, и исключает ответы модели, служебный контекст, дублирующиеся записи и внутренние сессии субагентов.

## Требования

- JDK 17 или новее
- sbt

## Запуск

Программа читает настройки из:

- `~/.config/llm-prompts/config.properties`;
- переменных окружения как fallback для настроек, отсутствующих в файле.

Если задана непустая переменная `XDG_CONFIG_HOME`, файл читается из `$XDG_CONFIG_HOME/llm-prompts/config.properties`. В противном случае используется `~/.config/llm-prompts/config.properties`.

Создайте каталог и файл конфигурации:

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

Поддерживаемые свойства и соответствующие переменные окружения:

| Свойство | Переменная окружения | Назначение |
| --- | --- | --- |
| `codex.sessions.directory` | `LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY` | Корневая папка с JSONL-сессиями Codex (необязательно) |
| `deepseek.conversations.directory` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_DIRECTORY` | Папка с архивами `deepseek_data-YYYY-MM-DD.zip` (необязательно, наивысший приоритет DeepSeek) |
| `deepseek.conversations.file` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE` | JSON-файл с беседами DeepSeek (необязательно) |
| `deepseek.conversations.archive` | `LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE` | ZIP-архив DeepSeek с файлом `conversations.json` (необязательно, имеет приоритет над JSON-файлом) |
| `output.directory` | `LLM_PROMPTS_OUTPUT_DIRECTORY` | Папка для записи выгрузки |
| `timezone.offset.hours` | `LLM_PROMPTS_TIMEZONE_OFFSET_HOURS` | Часовой пояс вывода: целое число часов от GMT от `-18` до `+18` (необязательно, по умолчанию Москва, `+3`) |

Значения из `config.properties` имеют приоритет. Fallback применяется отдельно к каждой настройке, поэтому файл и переменные окружения можно комбинировать. Пути, начинающиеся с `~/`, раскрываются относительно домашнего каталога текущего пользователя.

Все timestamp в выгрузке преобразуются в заданное фиксированное смещение относительно GMT. Если `timezone.offset.hours` отсутствует, используется московское время (`GMT+3`). Это фиксированное смещение без правил перехода на летнее время.

Также можно задать настройки только через окружение:

```bash
export LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY="$HOME/.codex/sessions"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_DIRECTORY="$HOME/Downloads"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE="$HOME/Downloads/deepseek-conversations.json"
export LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE="$HOME/Downloads/deepseek-data.zip"
export LLM_PROMPTS_OUTPUT_DIRECTORY="$HOME/llm-prompt-dumps"
export LLM_PROMPTS_TIMEZONE_OFFSET_HOURS="+3"
```

Запускайте программу без аргументов из каталога проекта:

```bash
sbt run
```

## Формат выгрузки

Выгрузка Codex записывается в `<выходная-папка>/codex`, чтобы корень выходного каталога оставался свободным для экспортеров других LLM. Для каждой рабочей папки, найденной в сессиях, программа создаёт один UTF-8-файл `prompts.txt`. Путь рабочей папки повторяется внутри каталога Codex.

Codex выгружается только тогда, когда настроено свойство `codex.sessions.directory` или соответствующая переменная окружения.

Например, сессии из рабочей папки `/home/user/project` попадут в:

```text
<выходная-папка>/codex/home/user/project/prompts.txt
```

Каждый файл содержит:

- ID сессий и количество промтов в каждой сессии;
- исходную рабочую папку;
- все пользовательские промты в хронологическом порядке;
- ID сессии перед каждым промтом;
- ISO 8601 timestamp каждого промта.

Также программа создаёт `<выходная-папка>/codex/all-prompts.txt`. В этом файле промты из всех рабочих папок объединены в единый хронологический поток. Используется тот же формат, но перед каждым промтом дополнительно указывается рабочая папка.

При повторном запуске существующие `prompts.txt` и `all-prompts.txt` полностью перезаписываются. Промты не дописываются и не дублируются. Программа не удаляет устаревшие файлы для рабочих папок, которых больше нет во входных сессиях.

### DeepSeek

Данные DeepSeek можно передать как папку через `deepseek.conversations.directory`, ZIP-архив через `deepseek.conversations.archive` или JSON-файл через `deepseek.conversations.file`. Приоритет: папка, затем архив, затем JSON-файл.

В режиме папки читаются все обычные файлы с именами вида `deepseek_data-YYYY-MM-DD.zip`. Каждый архив должен содержать ровно один `conversations.json`. Промты, повторяющиеся между архивами, дедуплицируются по ID беседы, timestamp и тексту, поэтому новый архив с полной историей можно просто положить рядом со старыми.

Нечитаемый архив, отсутствие `conversations.json`, некорректный JSON или неподдерживаемый корневой формат репортятся в stderr, после чего архив пропускается. Из частично корректного JSON выгружаются все пригодные REQUEST-фрагменты, а некорректные беседы, сообщения или фрагменты пропускаются и группируются в предупреждения с количеством. Затем обработка продолжается со следующими архивами.

Программа читает `message.fragments[].content` из фрагментов с типом `REQUEST`. Промты сортируются по `message.inserted_at` и записываются в один файл:

```text
<выходная-папка>/deepseek/all-prompts.txt
```

Настройки DeepSeek необязательны. Если все они отсутствуют, DeepSeek пропускается. Если не настроены ни Codex, ни DeepSeek, программа успешно завершается без выгрузки. `output.directory` остаётся обязательной настройкой.

### Общая выгрузка

Если настроен хотя бы один источник, программа также создаёт:

```text
<выходная-папка>/all-prompts.txt
```

В этом файле промты Codex и DeepSeek объединены в единый хронологический поток. Для каждого промта указываются тип `LLM` (`codex` или `deepseek`), ID сессии, timestamp, текст и рабочая папка, если источник содержит такую информацию.

## Тесты

```bash
sbt test
```
