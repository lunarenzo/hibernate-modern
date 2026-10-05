package lunatech.hibernate.data.model;

import java.util.UUID;

/** No live command sender survives an asynchronous operation. */
public sealed interface Recipient {
    record PlayerRecipient(UUID id) implements Recipient { }
    record ConsoleRecipient() implements Recipient { }
}
