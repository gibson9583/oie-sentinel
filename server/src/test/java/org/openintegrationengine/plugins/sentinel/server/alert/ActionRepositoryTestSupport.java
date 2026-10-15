package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;

import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;

/** Keeps dispatcher unit tests executing both authorization passes without a database. */
final class ActionRepositoryTestSupport {
    private ActionRepositoryTestSupport() { }

    static MockedStatic<ActionRepository> mockActions() {
        MockedStatic<ActionRepository> actions = mockStatic(ActionRepository.class);
        actions.when(() -> ActionRepository.validateDispatch(anyCollection(), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    Runnable validateActions = call.getArgument(1);
                    Runnable validateState = call.getArgument(2);
                    validateState.run();
                    validateActions.run();
                    validateState.run();
                    return null;
                });
        return actions;
    }
}
