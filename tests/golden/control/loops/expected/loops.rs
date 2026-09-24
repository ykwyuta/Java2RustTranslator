//! Translated from `Loops` (Loops.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

pub fn sum(values: JArray<i32>) -> i32 {
    let mut total: i32 = 0;
    {
        let __j2r_arr0: JArray<i32> = values.clone();
        let mut __j2r_i0: i32 = 0;
        while __j2r_i0 < __j2r_arr0.length() {
            let v: i32 = __j2r_arr0.get(__j2r_i0);
            total = total.wrapping_add(v);
            __j2r_i0 = __j2r_i0.wrapping_add(1);
        }
    }
    total
}

pub fn main(args: JArray<JString>) {
    {
        let mut i: i32 = 0;
        while i < 3 {
            'outer_body: {
                {
                    let mut j: i32 = 0;
                    while j < 3 {
                        if j == 2 {
                            break 'outer_body;
                        }
                        jrt::io::system_out().println(&jconcat!(i, ",", j));
                        j = j.wrapping_add(1);
                    }
                }
            }
            i = i.wrapping_add(1);
        }
    }
    let mut k: i32 = 0;
    loop {
        k = k.wrapping_add(1);
        if !(k < 5) {
            break;
        }
    }
    jrt::io::system_out().println(&sum(JArray::from_vec(vec![1, 2, 3])).wrapping_add(k));
}
