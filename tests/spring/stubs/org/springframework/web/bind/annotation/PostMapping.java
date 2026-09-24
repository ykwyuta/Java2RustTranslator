package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface PostMapping {
    String[] value() default {};

    String[] path() default {};
}
