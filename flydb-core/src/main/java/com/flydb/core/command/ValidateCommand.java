package com.flydb.core.command;

import java.util.List;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.FlydbValidationException;
import com.flydb.core.exception.ValidationProblem;

/** validate：只读、不加锁、一次聚合全部问题。 */
public final class ValidateCommand {
    private final FlydbConfiguration configuration;

    public ValidateCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public void execute() {
        try (CommandRuntime runtime = CommandRuntime.open(configuration, false)) {
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_VALIDATE);
            List<ValidationProblem> problems = MigrationValidator.validate(
                    MigrationInfoAssembler.assemble(runtime.resolved(), runtime.applied()));
            if (!problems.isEmpty()) {
                throw new FlydbValidationException(problems);
            }
            callbacks.fire(Event.AFTER_VALIDATE);
        }
    }
}
