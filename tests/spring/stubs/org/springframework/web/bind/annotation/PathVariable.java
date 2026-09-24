package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface PathVariable {
    String value() default "";

    String name() default "";
}
