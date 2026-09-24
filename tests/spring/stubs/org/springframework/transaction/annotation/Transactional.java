package org.springframework.transaction.annotation;

/** テスト用のスタブ。 */
public @interface Transactional {
    boolean readOnly() default false;

    Propagation propagation() default Propagation.REQUIRED;

    Class<? extends Throwable>[] rollbackFor() default {};

    Class<? extends Throwable>[] noRollbackFor() default {};
}
