package be.wwx.hibernate.service;

import be.wwx.hibernate.data.model.IdleState;

/** Monotonic elapsed-time countdown. Confined to the global scheduler. */
public final class EmptyTimer {
    private final IdleState active = new IdleState.Active();
    private final IdleState idle = new IdleState.Idle();
    private final IdleState disabled = new IdleState.Disabled();
    private final IdleState blocked = new IdleState.Blocked();
    private IdleState state = active;

    public IdleState update(boolean enabled, boolean blacklisted, boolean empty,
                            boolean playerJoined, long delayNanos, long now) {
        if (!enabled) {
            state = disabled;
        } else if (blacklisted) {
            state = blocked;
        } else if (!empty) {
            state = active;
        } else {
            if (playerJoined || !(state instanceof IdleState.Waiting || state instanceof IdleState.Idle)) {
                state = new IdleState.Waiting(now);
            }
            if (state instanceof IdleState.Waiting waiting && now - waiting.emptySince() >= delayNanos) {
                state = idle;
            }
        }
        return state;
    }

    public void reset() {
        state = active;
    }
}
