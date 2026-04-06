package com.smartfreight.inbox.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// @Entity — JPA сущность, которая отображается на таблицу БД
@Entity
// @Table — имя таблицы в базе данных
@Table(name = "container")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Container {

    // Первичный ключ, автоматически генерируется БД
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Уникальный номер контейнера (например: MSCU1234567)
    // Поле уникальное — один контейнер не может быть записан дважды
    @Column(name = "container_number", nullable = false, unique = true)
    private String containerNumber;

    // Текущий статус контейнера (INCOMING, ARRIVED, DEPARTED и т.д.)
    @Column(name = "status")
    private String status;

    // ETA — Estimated Time of Arrival (приблизительное время прибытия)
    // Тип LocalDateTime = TIMESTAMP в PostgreSQL (дата + время)
    @Column(name = "eta")
    private LocalDateTime eta;

    // Текущее местоположение контейнера (на судне, на терминале, в хранилище и т.д.)
    @Column(name = "location")
    private String location;
}
