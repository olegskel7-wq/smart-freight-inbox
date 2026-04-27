# IDE Setup для подготовки к защите

**Настройка IntelliJ для быстрого поиска нужного кода на защиту**

---

## 1️⃣ ОТКРЫТЬ ПРОЕКТ В INTELLIJ

```
File → Open → c:\Users\olegs\Downloads\inbox\inbox
```

Подождать пока IntelliJ проиндексирует проект (1-2 минуты)

---

## 2️⃣ ОТКРЫТЬ ОСНОВНЫЕ ФАЙЛЫ В ОТДЕЛЬНЫХ ВКЛАДКАХ

Используй: `Ctrl+O` (быстрый поиск файла)

**Вкладка 1: SecurityConfig.java**
- Путь: `src/main/java/com/smartfreight/inbox/config/SecurityConfig.java`
- Зачем: Spring Security конфиг

**Вкладка 2: TicketProcessingService.java**
- Путь: `src/main/java/com/smartfreight/inbox/service/TicketProcessingService.java`
- Зачем: Главный бизнес-логика (AI pipeline)

**Вкладка 3: EmailTicket.java**
- Путь: `src/main/java/com/smartfreight/inbox/entity/EmailTicket.java`
- Зачем: JPA сущность, оптимистическая блокировка

**Вкладка 4: EmailIngestionService.java**
- Путь: `src/main/java/com/smartfreight/inbox/integration/EmailIngestionService.java`
- Зачем: Entry point для писем

**Вкладка 5: AdminController.java**
- Путь: `src/main/java/com/smartfreight/inbox/controller/AdminController.java`
- Зачем: Архитектура без @Transactional

---

## ⚡ SHORTCUTS ДЛЯ БЫСТРОГО ПОИСКА

| Shortcut | Зачем | Пример |
|----------|-------|--------|
| `Ctrl+G` | Go to Line | `Ctrl+G → 105` (прыгает на линию 105) |
| `Ctrl+F` | Find in File | `Ctrl+F → @Transactional` |
| `Ctrl+Shift+F` | Find in Project | `Ctrl+Shift+F → @ServiceActivator` |
| `Ctrl+B` | Go to Definition | Click на методе → прыгает в определение |
| `Ctrl+Click` | Same as Ctrl+B | Клик на методе с Ctrl |
| `F3` | Find Next | Переходит к следующему match |
| `Shift+F3` | Find Previous | Переходит к предыдущему match |
| `Ctrl+Q` | Show JavaDoc | На методе → показывает документацию |
| `Ctrl+H` | Type Hierarchy | На интерфейсе → все реализации |

---

## 🗂️ СТРУКТУРА ПАПОК В IDE

```
src/main/java/com/smartfreight/inbox/
├── config/
│   ├── SecurityConfig.java .................. ⭐ ОСНОВНОЕ
│   ├── DbUserDetailsService.java ........... ⭐ ОСНОВНОЕ
│   ├── ImapConfig.java ..................... ⭐ ОСНОВНОЕ
│   ├── WebClientConfig.java ............... ⭐ ОСНОВНОЕ
│   └── SmtpConfig.java
├── controller/
│   ├── AuthController.java ................ ⭐ ОСНОВНОЕ
│   ├── TicketViewController.java .......... ⭐ ОСНОВНОЕ
│   ├── AdminController.java .............. ⭐ ОСНОВНОЕ
│   └── ...
├── service/
│   ├── TicketProcessingService.java ....... ⭐⭐⭐ ГЛАВНОЕ
│   ├── AuthService.java ................... ⭐ ОСНОВНОЕ
│   ├── AiExtractionService.java .......... ⭐ ОСНОВНОЕ
│   ├── AiDraftingService.java ............ ⭐ ОСНОВНОЕ
│   └── ...
├── entity/
│   ├── EmailTicket.java ................... ⭐ ОСНОВНОЕ
│   └── ...
├── repository/
│   ├── EmailTicketRepository.java ......... ⭐ ОСНОВНОЕ
│   └── ...
├── integration/
│   └── EmailIngestionService.java ........ ⭐ ОСНОВНОЕ
├── exception/
│   └── GlobalExceptionHandler.java ....... ⭐ ОСНОВНОЕ
└── ...
```

---

## 🎨 ОПТИМАЛЬНЫЕ НАСТРОЙКИ IDE

### Тема (Theme)
```
File → Settings → Appearance & Behavior → Appearance
Theme: "Darcula" (тёмный, удобно для проектора)
```

### Размер шрифта
```
File → Settings → Editor → Font
Size: 14 (для экрана)
Size: 16 (для проектора)
```

### Отступы (Tabs)
```
File → Settings → Editor → General → Tabs
Size: 2 или 4 (по предпочтению)
```

---

## 🎯 БЫСТРЫЙ ПЛАН ЗАЩИТЫ В IDE

**Сценарий: Комиссия спрашивает "Объясни архитектуру"**

### Шаг 1: Spring Security (1-2 минуты)
```
1. Открой: SecurityConfig.java
2. Ctrl+G → 58 (SecurityFilterChain)
3. Показать: @RestControllerAdvice(annotations = RestController.class)
4. Прочитать комментарий (он там написан)
5. Объяснить своими словами
```

### Шаг 2: AI Pipeline (2-3 минуты)
```
1. Открой: TicketProcessingService.java
2. Ctrl+G → 65 (process method)
3. Показать: 3 шага в методе (line 91-116)
4. Объяснить: Step 1, Step 2, Step 3
```

### Шаг 3: Защита (1-2 минуты)
```
1. Открой: EmailTicket.java
2. Ctrl+G → 105 (@Version)
3. Показать: оптимистическая блокировка
4. Потом: Ctrl+G → 18 (UNIQUE constraint)
5. Показать: дедупликация
```

### Шаг 4: IMAP Integration (1 минута)
```
1. Открой: EmailIngestionService.java
2. Ctrl+G → 42 (@ServiceActivator)
3. Показать: IMAP IDLE адаптер
```

---

## 💡 ТЕМНЫЕ РЕЖИМЫ И HIGHLIGHTING

IntelliJ автоматически подсвечивает:

- 🟢 **Spring beans** (`@Service`, `@Controller`, `@Repository`)
- 🔵 **@Transactional** методы
- 🟠 **@Entity** классы
- ⚫ **@Autowired** зависимости

Это помогает быстро ориентироваться в коде!

---

## 📝 КОММЕНТАРИИ И ДОКУМЕНТАЦИЯ

**Все файлы уже закомментированы на РУССКОМ!**

### Поиск комментариев:
```
Ctrl+F → //              (однострочные комментарии)
Ctrl+F → /**             (javadoc блоки)
```

### Просмотр javadoc:
```
Ctrl+Q (когда курсор на методе)
Показывает полную документацию в popup-е
```

---

## 🐛 ОТЛАДКА (если нужно понять как работает)

### Запуск в режиме отладки:
```
Ctrl+Shift+D → Debug selected file
(или нажать зелёный Play с жучком)
```

### Управление отладкой:
```
F8  → Step over (пройти через строку)
F7  → Step into (зайти в метод)
Shift+F8 → Step out (выйти из метода)
F9  → Resume (продолжить выполнение)
```

### Breakpoint:
```
Кликнуть слева от номера линии
🔴 Красный кружок = breakpoint установлен
```

---

## ✅ ЧЕК-ЛИСТ ПЕРЕД ЗАЩИТОЙ

Убедись что:

- [ ] Все 5 основных вкладок открыты в IDE
- [ ] Знаю `Ctrl+G` (go to line) — < 5 сек на переход
- [ ] Знаю `Ctrl+F` (find in file) — быстрый поиск
- [ ] Знаю `Ctrl+B` (go to definition) — быстрый переход
- [ ] Знаю `Ctrl+Q` (show javadoc) — показать комментарий
- [ ] Могу прыгнуть на нужную строку за <5 сек
- [ ] Видны комментарии в коде (на русском)
- [ ] IDE на тёмной теме (Darcula)
- [ ] Шрифт достаточно крупный (14-16)
- [ ] Правое окно (File Tree) видно

---

## 🚀 ФИНАЛЬНЫЙ СОВЕТ

**IDE = твоя супер-сила на защиту**

Если забыл ответ → открыть IDE → показать код → прочитать комментарий.

Комиссия видит:
✅ Ты знаешь код
✅ Код хорошо документирован
✅ Ты профессионал

**Удачи! 🎓**
