package be.wwx.hibernate.data.model;

/** Only the global scheduler mutates the timer; readers receive an immutable state. */
public sealed interface IdleState {
    record Active() implements IdleState { }
    record Waiting(long emptySince) implements IdleState { }
    record Idle() implements IdleState { }
    record Disabled() implements IdleState { }
    record Blocked() implements IdleState { }
}
