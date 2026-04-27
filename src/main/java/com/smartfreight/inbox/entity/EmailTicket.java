package com.smartfreight.inbox.entity;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * JPA esybė, atitinkanti lentelę {@code email_ticket} duomenų bazėje.
 *
 * Vienas bilietas = vienas gautas el. laiškas su visu apdorojimo kontekstu:
 * siuntėjas, tema, turinys, ištrauktas konteineris, AI juodraštis, būsena.
 *
 * Minkštas trynimas ({@link SQLDelete}): vietoj DELETE vykdomas UPDATE SET deleted=true –
 * įrašai lieka DB GDPR tikslais, bet nematomi jokiuose SELECT užklausuose ({@link SQLRestriction}).
 *
 * Optimistinė užrakta ({@link Version}): apsaugo nuo lenktynės sąlygų,
 * kai du srautai vienu metu bando atnaujinti tą patį biletą.
 */
@Entity
@Table(
    name = "email_ticket",
    uniqueConstraints = @UniqueConstraint(columnNames = "message_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder

// Minkštas trynimas: vietoj DELETE → UPDATE SET deleted=true (versija reikalinga optimistinei užraktai)
@SQLDelete(sql = "UPDATE email_ticket SET deleted = true WHERE id=? AND version=?")

// Automatiškai prideda WHERE deleted=false prie visų SELECT užklausų
@SQLRestriction("deleted=false")
public class EmailTicket {

    /** Pirminis raktas. IDENTITY = PostgreSQL generuoja sekantį ID automatiškai. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unikalus laiško identifikatorius iš RFC 2822 antraštės "Message-ID".
     * Naudojamas dublikatų tikrinimui – tas pats laiškas neišsaugomas du kartus.
     */
    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    /** El. pašto siuntėjo adresas (pvz.: customer@company.com). */
    @Column(name = "sender_email")
    private String senderEmail;

    /** Laiško tema (Subject antraštė). */
    @Column(name = "subject")
    private String subject;

    /** Visas laiško turinys. TEXT SQL tipas – neriboto ilgio (skirtingai nuo VARCHAR). */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Konteinerio numeris, kurį AI ištraukė iš laiško teksto (pvz.: MSCU1234567). */
    @Column(name = "extracted_container_number")
    private String extractedContainerNumber;

    /**
     * Bilieto būsena gyvavimo cikle.
     * EnumType.STRING saugo enum kaip tekstą "NEW", ne skaičių –
     * tai svarbu: pridėjus naują būseną seni įrašai nesugenda.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.NEW;

    /** AI (Llama 3.1) sugeneruotas atsakymo juodraštis. Operatorius peržiūri ir išsiunčia. */
    @Column(name = "draft_reply", columnDefinition = "TEXT")
    private String draftReply;

    /**
     * Pakartotinių bandymų skaitiklis.
     * Kai AI laikinai nepasiekiamas – bilietas gauna RETRY_LATER būseną
     * ir šis skaitiklis didėja kiekvienam bandymui.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Minkšto trynimo žymė. false = įrašas aktyvus, true = "ištrintas" (GDPR).
     * SQLRestriction automatiškai filtruoja deleted=true įrašus.
     */
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /** Bilieto sukūrimo laikas. updatable=false – niekada nekeičiamas po išsaugojimo. */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Paskutinio pakeitimo laikas – atnaujinamas kiekvieno save() metu. */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Optimistinės užraktos versija.
     * Hibernate automatiškai didina šį skaičių kiekvieno UPDATE metu.
     * Jei du srautai nuskaito version=5 ir abu bando išsaugoti –
     * antrasis gaus ObjectOptimisticLockingFailureException.
     */
    @Version
    private Integer version;
}
