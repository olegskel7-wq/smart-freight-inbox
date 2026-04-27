package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA saugykla {@link EmailTicket} esybei.
 *
 * Paveldimas {@link JpaRepository} suteikia paruoštus CRUD metodus.
 * Papildomi metodai apibrėžti pagal Spring Data metodų pavadinimų konvenciją
 * arba JPQL užklausomis (@Query).
 */
@Repository
public interface EmailTicketRepository extends JpaRepository<EmailTicket, Long> {

    /**
     * Randa biletą pagal unikalų laiško identifikatorių (Message-ID antraštė).
     * Naudojama dublikatų tikrinimui: ar šis laiškas jau išsaugotas?
     *
     * @param messageId laiško Message-ID antraštės reikšmė
     * @return bilietas, jei rastas
     */
    Optional<EmailTicket> findByMessageId(String messageId);

    /**
     * Randa visus bilietus su nurodyta būsena.
     *
     * @param status ieškoma būsena
     * @return biletų sąrašas
     */
    List<EmailTicket> findByStatus(TicketStatus status);

    /**
     * Randa visus bilietus su nurodyta būsena ir nurodytu rikiavimo tvarka.
     * Naudojama UI sąraše norint vienu metu filtruoti ir rikiuoti.
     *
     * @param status   ieškoma būsena
     * @param sort     rikiavimo specifikacija
     * @return surikiuotas biletų sąrašas
     */
    List<EmailTicket> findByStatus(TicketStatus status, Sort sort);

    /**
     * Randa RETRY_LATER bilietus, kurie neviršijo bandymų limito.
     * RetryScheduler naudoja tai biletams pakartotiniam apdorojimui surasti.
     * maxRetries parametras apsaugo nuo begalinių kartojimosi ciklų.
     *
     * @param status     ieškoma būsena (paprastai RETRY_LATER)
     * @param maxRetries maksimalus retryCount (šis skaičius neįtraukiamas)
     * @return tinkamų kartoti biletų sąrašas
     */
    List<EmailTicket> findByStatusAndRetryCountLessThan(TicketStatus status, int maxRetries);

    /**
     * Grąžina TIK biletų ID su nurodytomis būsenomis (projekcija).
     *
     * Naudojama {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}
     * užduočių sąrašo kūrimui be viso esybių įkėlimo į atmintį. Privalumai:
     * – nekrauname sunkių TEXT laukų body/draft_reply (gali būti iki 10 MB);
     * – nesukuriame managed esybių Persistence Context, kurių nereikia;
     * – vėlesnis process(id) pats perskaitys biletą savo transakcijoje.
     *
     * @param statuses ieškamų būsenų kolekcija (pvz.: NEW, RETRY_LATER)
     * @return ID sąrašas, surikiuotas didėjimo tvarka (atkartojamumui)
     */
    @Query("SELECT t.id FROM EmailTicket t WHERE t.status IN :statuses ORDER BY t.id")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);

    /**
     * Masinis visų FAILED biletų atstatymas į NEW būseną (viena SQL operacija).
     *
     * Naudojama {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}
     * prieš konvejerio paleidimą: FAILED biletai pažymimi kaip NEW ir
     * retry_count nulinamas, kad TicketProcessingService.process() juos priimtų.
     *
     * Masinio atnaujinimo privalumai prieš findAll + setStatus + save:
     * – 1 SQL UPDATE prieš N SELECT + N UPDATE;
     * – esybės nekraunamos į Persistence Context (atmintis + GC);
     * – trumpa transakcija, HikariCP ryšys iš karto atlaisvinamas.
     *
     * Perspėjimas: masinis atnaujinimas NESINCHRONIZUOJA Persistence Context.
     * Jei toje pačioje transakcijoje šie biletai buvo nuskaityti kaip managed –
     * Hibernate to nematys. Čia naudojantis kodas biletų neskaitė, todėl OK.
     *
     * @return atnaujintų eilučių skaičius
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.status = com.smartfreight.inbox.entity.enums.TicketStatus.NEW, " +
           "       t.retryCount = 0, " +
           "       t.updatedAt = CURRENT_TIMESTAMP " +
           " WHERE t.status = com.smartfreight.inbox.entity.enums.TicketStatus.FAILED")
    int resetFailedToNew();
}
