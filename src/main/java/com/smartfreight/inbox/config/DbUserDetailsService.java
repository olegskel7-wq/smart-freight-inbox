package com.smartfreight.inbox.config;

import com.smartfreight.inbox.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * {@link UserDetailsService} implementacija virš duomenų bazės (lentelė {@code app_user}).
 *
 * Pakeičia ankstesnį {@code InMemoryUserDetailsManager} su hardcode'intu demo naudotoju.
 * Dabar Spring Security prisijungimo metu (tiek per formų prisijungimą {@code /login},
 * tiek per REST {@code /api/auth/login}) naudotojo duomenis skaito tik iš vieno šaltinio –
 * {@code app_user} lentelės.
 *
 * Privalumai:
 * – vienintelis tiesos šaltinis naudotojams (DB, ne in-memory + hardcode);
 * – naudotojai pridedami per Flyway migracijas (V2/V3 demo naudotojui operator@smartfreight.com);
 * – paruošta ateičiai: slaptažodžio keitimas, paskyros blokavimas, vaidmenys, multi-tenancy.
 *
 * Spring Security automatiškai aptinka šį bean'ą kaip vienintelį {@link UserDetailsService}.
 *
 * Svarbu: šis klasė NETURI pati koduoti slaptažodžio. Koduotas slaptažodis jau yra DB
 * (įrašytas Flyway migracija su BCrypt hashu), o įvesto slaptažodžio palyginimą
 * atlieka {@code DaoAuthenticationProvider} per {@link org.springframework.security.crypto.password.PasswordEncoder}.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsService.class);

    /** Numatytasis vaidmuo, jei DB naudotojo {@code role} laukas yra NULL. */
    private static final String DEFAULT_ROLE = "OPERATOR";

    private final UserRepository userRepository;

    public DbUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Įkelia naudotojo duomenis pagal prisijungimo vardą (el. paštą).
     *
     * Metodą automatiškai kviečia Spring Security autentifikacijos metu.
     * Grąžintas {@link UserDetails} objektas turi el. paštą, BCrypt hashą
     * ir vaidmenų sąrašą; slaptažodžio palyginimą atlieka Spring Security.
     *
     * Saugumo pastabos:
     * – {@link UsernameNotFoundException} metamas su generalizuotu pranešimu –
     *   klientui neatskleidžiama ar naudotojas egzistuoja (logai turi detalę);
     * – jei vaidmuo DB nenustatytas, priskiriamas saugus numatytasis OPERATOR,
     *   kad nebūtų "naudotojas be vaidmens" (Spring Security tokiu atveju skundžiasi).
     *
     * @param email el. pašto adresas – jis pats ir yra prisijungimo vardas
     * @return {@link UserDetails} objektas, suprantamas Spring Security
     * @throws UsernameNotFoundException jei naudotojas su tokiu el. paštu nerastas
     */
    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(u -> {
                    String role = (u.getRole() == null || u.getRole().isBlank())
                            ? DEFAULT_ROLE
                            : u.getRole();
                    // Naudojamas FQN, nes trumpas importas konfliktuotų
                    // su com.smartfreight.inbox.entity.User klase
                    return org.springframework.security.core.userdetails.User
                            .withUsername(u.getEmail())
                            .password(u.getPassword())
                            .roles(role)
                            .build();
                })
                .orElseThrow(() -> {
                    log.warn("User not found in database: {}", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });
    }
}
