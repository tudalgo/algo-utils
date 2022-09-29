package org.tudalgo.algoutils.tutor.general.test.basic;

import org.tudalgo.algoutils.tutor.general.Environment;
import org.tudalgo.algoutils.tutor.general.callable.Callable;
import org.tudalgo.algoutils.tutor.general.test.TestOfCall;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public class BasicTestOfCall extends BasicTest<BasicTestOfCall, BasicResultOfCall> implements TestOfCall<BasicTestOfCall, BasicResultOfCall> {

    private final BooleanSupplier evaluator;

    public BasicTestOfCall(Environment environment, Object expectation, BooleanSupplier evaluator) {
        super(environment, expectation);
        this.evaluator = evaluator;
    }

    @Override
    public BasicResultOfCall run(Callable callable) {
        Objects.requireNonNull(callable, "callable must not be null");
        try {
            callable.call();
        } catch (Throwable throwable) {
            return new BasicResultOfCall(environment, this, false, throwable);
        }
        return new BasicResultOfCall(environment, this, evaluator.getAsBoolean());
    }

    public static final class Builder extends BasicTest.Builder<BasicTestOfCall, BasicResultOfCall, Builder> implements TestOfCall.Builder<BasicTestOfCall, BasicResultOfCall, Builder> {

        private BooleanSupplier evaluator;

        private Builder(Environment environment) {
            super(environment);
        }

        @Override
        public BasicTestOfCall build() {
            return new BasicTestOfCall(environment, expectation, evaluator);
        }

        @Override
        public Builder evaluator(BooleanSupplier evaluator) {
            this.evaluator = evaluator;
            return this;
        }

        public static final class Factory extends BasicTest.Builder.Factory<BasicTestOfCall, BasicResultOfCall, Builder> implements TestOfCall.Builder.Factory<BasicTestOfCall, BasicResultOfCall, Builder> {

            public Factory(Environment environment) {
                super(environment);
            }

            @Override
            public Builder builder() {
                return new Builder(environment);
            }
        }
    }
}
