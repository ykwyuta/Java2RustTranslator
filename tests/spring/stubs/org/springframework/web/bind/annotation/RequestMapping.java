package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface RequestMapping {
    String[] value() default {};

    String[] path() default {};
}
