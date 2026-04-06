package com.smartfreight.inbox.exception;

// Пользовательское исключение: выбрасывается когда контейнер не найден в БД
public class ContainerNotFoundException extends RuntimeException {

    // Конструктор с сообщением об ошибке
    public ContainerNotFoundException(String message) {
        super(message);
    }

    // Конструктор с сообщением и причиной (другим исключением)
    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
