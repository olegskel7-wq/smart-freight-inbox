package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA saugykla {@link User} esybei.
 *
 * Naudojama {@link com.smartfreight.inbox.config.DbUserDetailsService}
 * naudotojo paieškai autentifikacijos metu.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Randa naudotoją pagal el. pašto adresą.
     * Naudojama prisijungimo metu ir naudotojo egzistavimo tikrinimui.
     *
     * @param email naudotojo el. pašto adresas
     * @return naudotojas, jei rastas
     */
    Optional<User> findByEmail(String email);
}
