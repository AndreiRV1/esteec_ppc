package ro.ppc.filegrid.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.account.Accounts.Account;
import ro.ppc.filegrid.account.Accounts.Bucket;
import ro.ppc.filegrid.account.Accounts.Consumption;
import ro.ppc.filegrid.account.Accounts.ConsumptionSummary;
import ro.ppc.filegrid.account.Accounts.Me;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.Users;
import ro.ppc.filegrid.fault.FaultRecordRepository;
import ro.ppc.filegrid.fault.FaultRecords;

/**
 * The logged-in user. Customers (username = client id, linked to a meter in
 * {@code customer_account}) also get their consumption and the faults in their area;
 * they can only ever read their own meter.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "Who is logged in; for customers: own consumption and faults in the area")
public class MeController {

    private static final Duration MAX_HOURLY_RANGE = Duration.ofDays(31);
    private static final Duration MAX_DAILY_RANGE = Duration.ofDays(366);

    private final AccountRepository accounts;
    private final FaultRecordRepository faults;

    public MeController(AccountRepository accounts, FaultRecordRepository faults) {
        this.accounts = accounts;
        this.faults = faults;
    }

    @Operation(summary = "Current user, roles and (for customers) meter and area")
    @GetMapping
    public Me me(@AuthenticationPrincipal Jwt user, Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5).toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        Optional<Account> account = account(user);
        return new Me(Users.username(user), roles,
                account.map(Account::region).orElse(null), account.map(Account::county).orElse(null),
                account.map(Account::locality).orElse(null), account.map(Account::meterId).orElse(null));
    }

    @Operation(summary = "Consumption of my meter per hour or day",
            description = "Defaults to the last 7 days. `from` is inclusive, `to` exclusive. "
                    + "At most 31 days per request for HOUR, 366 for DAY.")
    @GetMapping("/consumption")
    public Consumption consumption(@RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "HOUR") Bucket bucket,
                                   @AuthenticationPrincipal Jwt user) {
        Account account = require(user);
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        Duration max = bucket == Bucket.HOUR ? MAX_HOURLY_RANGE : MAX_DAILY_RANGE;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(max) > 0) {
            throw ApiException.badRequest("invalid_range",
                    "`from` must be before `to`, at most " + max.toDays() + " days apart for bucket " + bucket);
        }
        return new Consumption(account.meterId(), bucket,
                accounts.consumption(account.meterId(), start, end, bucket));
    }

    @Operation(summary = "Energy used this month so far and last month (kWh)")
    @GetMapping("/consumption/summary")
    public ConsumptionSummary summary(@AuthenticationPrincipal Jwt user) {
        return accounts.summary(require(user).meterId());
    }

    @Operation(summary = "Faults in my county that are not restored yet")
    @GetMapping("/faults")
    public List<FaultRecords.Summary> faults(@AuthenticationPrincipal Jwt user) {
        Account account = require(user);
        return faults.activeIn(account.region(), account.county());
    }

    /** The caller's account; a client whose racordare was accepted gets one on first use. */
    private Optional<Account> account(Jwt user) {
        String username = Users.username(user);
        Optional<Account> account = accounts.find(username);
        if (account.isPresent() || !isClient(user)) return account;
        return accounts.provisionFromAcceptedRequest(username, user.getSubject());
    }

    private static boolean isClient(Jwt user) {
        Object roles = user.getClaimAsMap("realm_access") == null ? null : user.getClaimAsMap("realm_access").get("roles");
        return roles instanceof java.util.Collection<?> c && c.contains("client");
    }

    private Account require(Jwt user) {
        return account(user).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "no_customer_account", "This user has no customer account linked to a meter"));
    }
}
