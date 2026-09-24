package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface ExceptionHandler {
    Class<? extends Throwable>[] value() default {};
}
