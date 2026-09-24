package org.springframework.transaction.annotation;

/** テスト用のスタブ。 */
public @interface Transactional {
    boolean readOnly() default false;
}
