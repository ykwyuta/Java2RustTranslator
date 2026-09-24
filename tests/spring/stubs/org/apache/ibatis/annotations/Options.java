package org.apache.ibatis.annotations;

/** テスト用のスタブ。 */
public @interface Options {
    boolean useGeneratedKeys() default false;

    String keyProperty() default "";

    String keyColumn() default "";
}
