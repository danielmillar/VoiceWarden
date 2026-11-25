package dev.voicewarden.core.storage;

import dev.voicewarden.api.VoiceMuteRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class InMemoryFallbackStore implements StorageProvider {

    private final String serverId;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<VoiceMuteRecord> mutes = new ArrayList<>();
    private final List<StrikeRecord> strikes = new ArrayList<>();
    private final List<VoiceReportRecord> reports = new ArrayList<>();
    private final AtomicLong strikeIds = new AtomicLong();
    private final AtomicLong reportIds = new AtomicLong();

    public InMemoryFallbackStore(String serverId) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    @Override
    public void saveMute(VoiceMuteRecord mute) {
        Objects.requireNonNull(mute, "mute");
        lock.lock();
        try {
            mutes.add(mute);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int deactivateActiveMutes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            int changed = 0;
            for (int i = 0; i < mutes.size(); i++) {
                VoiceMuteRecord mute = mutes.get(i);
                if (mute.playerId().equals(playerId) && mute.active()) {
                    mutes.set(i, mute.withActive(false));
                    changed++;
                }
            }
            return changed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<VoiceMuteRecord> findActiveMute(UUID playerId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            for (int i = mutes.size() - 1; i >= 0; i--) {
                VoiceMuteRecord mute = mutes.get(i);
                if (mute.playerId().equals(playerId) && mute.isEffectivelyActive(now)) {
                    return Optional.of(mute);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<VoiceMuteRecord> findAllActiveMutes(Instant now) {
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            List<VoiceMuteRecord> active = new ArrayList<>();
            for (int i = mutes.size() - 1; i >= 0; i--) {
                if (mutes.get(i).isEffectivelyActive(now)) {
                    active.add(mutes.get(i));
                }
            }
            return active;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<VoiceMuteRecord> findMuteHistory(UUID playerId, int limit) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            List<VoiceMuteRecord> history = new ArrayList<>();
            for (int i = mutes.size() - 1; i >= 0 && history.size() < limit; i--) {
                if (mutes.get(i).playerId().equals(playerId)) {
                    history.add(mutes.get(i));
                }
            }
            history.sort(Comparator.comparing(VoiceMuteRecord::createdAt).reversed());
            return history;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int countMutesInWindow(UUID playerId, Instant since, boolean networkWide) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(since, "since");
        lock.lock();
        try {
            return (int) mutes.stream()
                    .filter(mute -> mute.playerId().equals(playerId))
                    .filter(mute -> !mute.createdAt().isBefore(since))
                    .filter(mute -> networkWide || mute.serverId().equals(serverId))
                    .count();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StrikeRecord recordStrike(StrikeRecord strike) {
        Objects.requireNonNull(strike, "strike");
        StrikeRecord saved = strike.withId(strikeIds.incrementAndGet());
        lock.lock();
        try {
            strikes.add(saved);
        } finally {
            lock.unlock();
        }
        return saved;
    }

    @Override
    public int countActiveStrikes(UUID playerId, Instant since, boolean networkWide) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(since, "since");
        lock.lock();
        try {
            return (int) strikes.stream()
                    .filter(strike -> strike.playerId().equals(playerId))
                    .filter(strike -> !strike.pardoned())
                    .filter(strike -> !strike.createdAt().isBefore(since))
                    .filter(strike -> networkWide || strike.serverId().equals(serverId))
                    .count();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int pardonStrikes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            int changed = 0;
            for (int i = 0; i < strikes.size(); i++) {
                StrikeRecord strike = strikes.get(i);
                if (strike.playerId().equals(playerId) && !strike.pardoned()) {
                    strikes.set(i, strike.withPardoned(true));
                    changed++;
                }
            }
            return changed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public VoiceReportRecord saveReport(VoiceReportRecord report) {
        Objects.requireNonNull(report, "report");
        VoiceReportRecord saved = report.withId(-reportIds.incrementAndGet());
        lock.lock();
        try {
            reports.add(saved);
        } finally {
            lock.unlock();
        }
        return saved;
    }

    @Override
    public List<VoiceReportRecord> findReports(boolean unreviewedOnly, int limit) {
        lock.lock();
        try {
            List<VoiceReportRecord> found = new ArrayList<>();
            for (int i = reports.size() - 1; i >= 0 && found.size() < limit; i--) {
                VoiceReportRecord report = reports.get(i);
                if (!unreviewedOnly || !report.reviewed()) {
                    found.add(report);
                }
            }
            return found;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<VoiceReportRecord> findReportById(long id) {
        lock.lock();
        try {
            return reports.stream().filter(report -> report.id() == id).findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean markReportReviewed(long id, String reviewer, Instant reviewedAt) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        lock.lock();
        try {
            for (int i = 0; i < reports.size(); i++) {
                VoiceReportRecord report = reports.get(i);
                if (report.id() == id && !report.reviewed()) {
                    reports.set(i, report.markedReviewed(reviewer, reviewedAt));
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int countOpenReportsByReporter(UUID reporterId) {
        Objects.requireNonNull(reporterId, "reporterId");
        lock.lock();
        try {
            return (int) reports.stream()
                    .filter(report -> report.reporterId().equals(reporterId) && !report.reviewed())
                    .count();
        } finally {
            lock.unlock();
        }
    }

    public int pruneBefore(Instant cutoff) {
        return pruneBefore(cutoff, cutoff);
    }

    public int pruneBefore(Instant cutoff, Instant now) {
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            int before = mutes.size() + strikes.size() + reports.size();
            mutes.removeIf(mute -> mute.createdAt().isBefore(cutoff) && !mute.isEffectivelyActive(now));
            strikes.removeIf(strike -> strike.createdAt().isBefore(cutoff));
            reports.removeIf(report -> report.createdAt().isBefore(cutoff));
            return before - mutes.size() - strikes.size() - reports.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            mutes.clear();
            strikes.clear();
            reports.clear();
        } finally {
            lock.unlock();
        }
    }
}
