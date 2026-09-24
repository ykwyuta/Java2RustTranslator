package org.apache.ibatis.annotations;

/** テスト用のスタブ。 */
public @interface Result {
    boolean id() default false;

    String column() default "";

    String property() default "";
}
