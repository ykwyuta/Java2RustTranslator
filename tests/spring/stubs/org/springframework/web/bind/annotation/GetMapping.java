package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface GetMapping {
    String[] value() default {};

    String[] path() default {};
}
