package com.cheetah.racer.security;

import com.cheetah.racer.config.RacerProperties;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates incoming message sender identifiers against a configured allow-list
 * and/or deny-list.
 *
 * <p>Configured via:
 * <ul>
 *   <li>{@code racer.security.allowed-senders} — if non-empty, only the listed senders
 *       are permitted; all others are dropped.</li>
 *   <li>{@code racer.security.denied-senders} — senders in this list are always rejected,
 *       regardless of the allow-list.</li>
 * </ul>
 *
 * <p>Evaluation order: deny-list is checked first; if the sender passes, the allow-list
 * is checked (only when non-empty).
 *
 * <p>When both lists are empty the filter is inactive ({@link #isActive()} returns
 * {@code false}) and all senders pass through.
 */
public class RacerSenderFilter {

    private final Set<String> allowedSenders;
    private final Set<String> deniedSenders;

    public RacerSenderFilter(RacerProperties properties) {
        List<String> allowed = properties.getSecurity().getAllowedSenders();
        List<String> denied  = properties.getSecurity().getDeniedSenders();
        this.allowedSenders = (allowed != null && !allowed.isEmpty())
                ? Collections.unmodifiableSet(new HashSet<>(allowed))
                : Collections.emptySet();
        this.deniedSenders = (denied != null && !denied.isEmpty())
                ? Collections.unmodifiableSet(new HashSet<>(denied))
                : Collections.emptySet();
    }

    /**
     * Returns {@code true} if the given sender is permitted to deliver messages.
     *
     * @param sender the value of {@link com.cheetah.racer.model.RacerMessage#getSender()};
     *               {@code null} is treated as an empty string
     */
    public boolean isAllowed(String sender) {
        String s = sender == null ? "" : sender;
        if (deniedSenders.contains(s)) return false;
        if (!allowedSenders.isEmpty() && !allowedSenders.contains(s)) return false;
        return true;
    }

    /**
     * Returns {@code true} if at least one filter list is non-empty and the filter
     * should be applied to incoming messages.
     */
    public boolean isActive() {
        return !allowedSenders.isEmpty() || !deniedSenders.isEmpty();
    }
}
