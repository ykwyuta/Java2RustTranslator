package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface RequestParam {
    String value() default "";

    String name() default "";

    boolean required() default true;

    String defaultValue() default "";
}
