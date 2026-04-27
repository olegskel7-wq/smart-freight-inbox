# Как использовать комментарии в коде во время подготовки к защите

## 📖 Рекомендуемый порядок изучения

### День 1-2: Общая архитектура (1-2 часа)

1. **Прочитай CODE_REVIEW_FOR_DEFENSE.md** (весь файл)
   - Получишь общее понимание архитектуры
   - Выделены "моменты из коробки" (Spring Security, Lombok, JPA и т.д.)

2. **Посмотри на диаграммы в MERMAID_DIAGRAMS.md**
   - Визуальное представление flow'ов
   - Понимание между component-взаимодействие

### День 3-5: Глубокий разбор в IDE (3-4 часа)

Открой IntelliJ и переходи по файлам согласно CODE_COMMENTS_INDEX.md:

**Блок 1: Аутентификация (1 час)**
```
SecurityConfig.java ............ как работает Spring Security
DbUserDetailsService.java ...... откуда берутся юзеры
AuthService.java .............. как логинить через REST API
AuthController.java ........... /api/auth/login endpoint
```

**Блок 2: AI Pipeline (1.5 часа)**
```
TicketProcessingService.java ... оркестрация (main logic)
AiExtractionService.java ....... Step 1: Qwen extraction
AiDraftingService.java ......... Step 2: Llama drafting
EmailIngestionService.java ..... Entry point из IMAP
```

**Блок 3: Данные и Конфиги (1 час)**
```
entity/EmailTicket.java ........ @Entity с @Version, @Unique
repository/EmailTicketRepository.java . Spring Data JPA
ImapConfig.java ............... IMAP IDLE конвейер
WebClientConfig.java .......... таймауты для AI
```

**Блок 4: UI и Error Handling (0.5 часа)**
```
TicketViewController.java ...... Thymeleaf страницы
AdminController.java .......... Adminка (localhost-only)
GlobalExceptionHandler.java ... Error handling
```

### День 6-7: Репетиция (1-2 часа)

1. **Репетиция "2 минуты архитектуры":**
   - Используй сценарий из CODE_REVIEW_FOR_DEFENSE.md (часть "БЫСТРЫЙ СПРАВОЧНИК")
   - Покажи на примерах в IDE какие компоненты за что отвечают

2. **Репетиция Q&A:**
   - Прочитай "ГОТОВЫЕ ОТВЕТЫ ДЛЯ ЗАЩИТЫ" в CODE_REVIEW_FOR_DEFENSE.md
   - На каждый вопрос → откройте файл в IDE и покажите конкретный код
   - Например, на вопрос "Как вы защищаетесь от race condition?" → откройте entity/EmailTicket.java:105-106

---

## 💻 В IDE: Как быстро найти нужный код

### IntelliJ shortcuts для поиска:

**Ctrl+G** (Go to Line)
```
security/SecurityConfig.java:38 → перейти на line 38
```

**Ctrl+F** (Find in File)
```
Ищешь "@Transactional" в TicketProcessingService.java
Находишь все места где используется
```

**Ctrl+Shift+F** (Find in Project)
```
Ищешь "@ServiceActivator" по всему проекту
Видишь что это только в EmailIngestionService.java
```

**Ctrl+B** (Go to Definition)
```
Кликаешь на TicketProcessingService.process()
→ перейдёшь прямо в метод
```

---

## 🎤 Стратегия объяснения на защите

### Стратегия 1: Сверху вниз (High-level first)

```
1. "Система состоит из 4 слоёв: Controllers → Services → Repositories → Database"
   → открыть пакеты в IDE, показать файлы

2. "Главный бизнес-процесс это AI-конвейер:"
   → открыть TicketProcessingService, показать 3 шага (lines 91-116)

3. "Шаг 1: Извлечение номера контейнера через Qwen 2.5"
   → открыть AiExtractionService, показать:
   - вызов к Ollama (line 68-74)
   - fallback regex (line 110-118)

4. "Данные защищены через:"
   → открыть EmailTicket.java:
   - UNIQUE constraint на message_id (line 18)
   - @Version для race condition (line 105-106)
```

### Стратегия 2: Снизу вверх (Code-first)

```
1. "Вот у нас Entity (основной класс):"
   → открыть EmailTicket.java, показать поля, аннотации
   → объяснить @Entity, @Table, @Enumerated, @Version

2. "Спешите как мы получаем данные?"
   → открыть EmailTicketRepository, показать методы
   → объяснить findByMessageId для дедупликации

3. "Вот бизнес-логика которая работает с данными:"
   → открыть TicketProcessingService, показать process()
   → пройтись по шагам

4. "И вот как это всё запускается:"
   → открыть EmailIngestionService, показать @ServiceActivator
   → объяснить afterCommit callback
```

### Стратегия 3: По компонентам (Component-based)

Вместо линейного, можно дать выбор комиссии:

```
"Архитектура из 4 блоков. На какой вы хотите fokus?
1. Аутентификация и безопасность?
2. AI-конвейер и обработка?
3. Данные и хранилище?
4. Integration с внешними системами?"

Потом показываешь нужный блок в деталях.
```

---

## 🔍 Как читать комментарии в коде

Все комментарии написаны так:

### Комментарии в class-уровне:
```java
/**
 * Описание что делает класс.
 * 
 * Используется для...
 * Входные данные...
 * Выходные данные...
 */
```
Прочитай сразу когда открыл файл.

### Комментарии в method-уровне:
```java
/**
 * Описание что делает метод.
 * 
 * Алгоритм:
 *  1. Шаг первый
 *  2. Шаг второй
 * 
 * Защитные соображения:
 *  - ...
 * 
 * @param name описание параметра
 * @return что возвращает
 */
```
Сюда смотреть когда разбираешь логику.

### Inline комментарии:
```java
// Одна строка комментария перед/после строки кода
// Объясняет ЗАЧЕМ эта строка, не ЧТО она делает

// Многострочный block комментарий
// для объяснения целой логики
```

---

## 📊 Матрица: "Какой файл за что отвечает"

```
                        | Аутентификация | AI Pipeline | Данные | Мониторинг | UI
────────────────────────┼────────────────┼─────────────┼────────┼────────────┼─────────────
SecurityConfig.java     | ★★★            |             |        |            |
DbUserDetailsService    | ★★★            |             |        |            |
AuthService             | ★★★            |             |        |            |
AuthController          | ★★★            |             |        |            |
────────────────────────┼────────────────┼─────────────┼────────┼────────────┼─────────────
TicketProcessingService |                | ★★★         | ★★     | ★★         |
AiExtractionService     |                | ★★★         |        |            |
AiDraftingService       |                | ★★★         |        |            |
EmailIngestionService   |                | ★★          | ★★     |            |
────────────────────────┼────────────────┼─────────────┼────────┼────────────┼─────────────
EmailTicket.java        |                |             | ★★★    | ★          |
EmailTicketRepository   |                |             | ★★★    |            |
────────────────────────┼────────────────┼─────────────┼────────┼────────────┼─────────────
ImapConfig.java         |                | ★★          |        |            |
WebClientConfig.java    |                | ★★          |        |            |
────────────────────────┼────────────────┼─────────────┼────────┼────────────┼─────────────
GlobalExceptionHandler  |                |             |        |            | ★★
TicketViewController    |                |             | ★      |            | ★★★
AdminController         |                | ★           |        |            |
```

★★★ = primary responsibility
★★ = secondary
★ = minor

---

## 🎯 Во время защиты: Шпаргалка

Распечатай эту таблицу и возьми с собой (или скопируй на телефон):

```
╔═══════════════════════════════════════════════════════════════╗
║                    ЧТО ГОВОРИТЬ / ЧТО ОТКРЫТЬ                ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Объясни архитектуру"                                 ║
║ → CODE_REVIEW_FOR_DEFENSE.md, часть "АРХИТЕКТУРНЫЙ ОБЗОР"     ║
║ → в IDE: SecurityConfig.java (линия 58)                       ║
║ → в IDE: TicketProcessingService.java (линия 65)              ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Как защищаетесь от race condition?"                  ║
║ → CODE_REVIEW_FOR_DEFENSE.md, "Оптимистическая блокировка"    ║
║ → в IDE: EmailTicket.java (линии 102-106)                     ║
║ → в IDE: TicketProcessingService.java (линия 129-136)         ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Где вы используете Spring Security?"                 ║
║ → CODE_REVIEW_FOR_DEFENSE.md, часть "Spring Security"         ║
║ → в IDE: SecurityConfig.java (весь файл)                      ║
║ → в IDE: DbUserDetailsService.java (весь файл)                ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Как вы справляетесь если AI недоступен?"             ║
║ → в IDE: AiExtractionService.java (линии 102-105)             ║
║ → в IDE: AiDraftingService.java (линии 94-97)                 ║
║ → CODE_REVIEW_FOR_DEFENSE.md, раздел Q&A                      ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Объясни AI-конвейер"                                 ║
║ → в IDE: TicketProcessingService.java (линии 91-127)          ║
║ → CODE_REVIEW_FOR_DEFENSE.md, "Компонент 2: AI Processing"    ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Как вы используете БД?"                              ║
║ → в IDE: EmailTicket.java (всё)                               ║
║ → в IDE: EmailTicketRepository.java (всё)                     ║
║ → CODE_REVIEW_FOR_DEFENSE.md, "Компонент 4: Data Persistence" ║
╠═══════════════════════════════════════════════════════════════╣
║ Вопрос: "Зачем вы используете Lombok?"                        ║
║ → CODE_REVIEW_FOR_DEFENSE.md, "Lombok"                        ║
║ → в IDE: EmailTicket.java (линии 21-31)                       ║
╚═══════════════════════════════════════════════════════════════╝
```

---

## ✅ Чек-лист перед защитой

- [ ] Прочитал весь CODE_REVIEW_FOR_DEFENSE.md
- [ ] Прочитал CODE_COMMENTS_INDEX.md
- [ ] Открывал в IDE каждый файл из "Блока 1-4"
- [ ] Понимаю как работает TicketProcessingService.process()
- [ ] Могу объяснить дедупликацию + оптимистическую блокировку
- [ ] Знаю 5+ компонент "из коробки" Spring (Security, JPA, Integration, Actuator, Flyway)
- [ ] Могу дать fallback для каждого failure scenario
- [ ] Проговорил вслух "2 минуты архитектуры" минимум 3 раза
- [ ] Знаю где в коде найти ответ на любой вопрос из CODE_REVIEW_FOR_DEFENSE.md

---

## 🚀 Финальный совет

**Когда комиссия задаст вопрос:**

1. **Не паникуй.** Ты знаешь ответ — он в CODE_REVIEW_FOR_DEFENSE.md
2. **Скажи один sentence** общего ответа
3. **Откройте IDE** и покажи конкретный код
4. **Прочитай комментарий** который уже там написан
5. **Объясни своими словами** опираясь на код

Пример:
```
Вопрос: "Как вы защищаетесь от двойной обработки одного письма?"

Твой ответ:
"Используем дедупликацию по Message-ID. Есть UNIQUE constraint на БД-уровне,
плюс проверка в коде перед сохранением.
[Открываешь IDE → EmailTicket.java → показываешь line 18]
Смотри, here @UniqueConstraint на message_id.
[Показываешь EmailIngestionService.java → line 77]
И перед save проверяем — если письмо уже есть, пропускаем его.
Даже если IMAP пришлёт письмо дважды после reconnect, БД отклонит второе."
```

Комиссия видит что ты:
- ✅ знаешь архитектуру
- ✅ умеешь читать собственный код
- ✅ понимаешь что происходит в production
- ✅ используешь "из коробки" решения правильно

Удачи на защите! 🎓
