//! Translated from `ItemMapper` (ItemMapper.java, ItemMapper.xml) by Java2RustTranslator.

use sqlx::{PgConnection, Postgres, QueryBuilder};
use sqlx::postgres::PgRow;

use crate::domain::{Category, Item};
use crate::mybatis::{Trim, column};

/// `<select id="findByIds">`
pub async fn find_by_ids(conn: &mut PgConnection, ids: &[i64]) -> sqlx::Result<Vec<Item>> {
    let mut query = QueryBuilder::<Postgres>::new(
        "SELECT id AS item_id, name AS item_name, category, price, note FROM items",
    );
    let mut where_clause = Trim::new("WHERE", "");
    if !ids.is_empty() {
        where_clause.part(&mut query, "", "");
        query.push("id IN (");
        for (i, id) in ids.iter().copied().enumerate() {
            if i > 0 {
                query.push(", ");
            }
            query.push_bind(id);
        }
        query.push(")");
    }
    query.build().try_map(item_map).fetch_all(conn).await
}

/// `<select id="search">`
pub async fn search(
    conn: &mut PgConnection,
    keyword: Option<&str>,
    categories: &[Category],
    order_by: &str,
) -> sqlx::Result<Vec<Item>> {
    let mut query = QueryBuilder::<Postgres>::new("");
    let pattern = format!("%{}%", keyword.unwrap_or("null"));
    query.push(" SELECT id, name, category, price, note FROM items");
    let mut trim = Trim::new("WHERE", "");
    if keyword.is_some() {
        trim.part(&mut query, "AND", "").push("name ILIKE ").push_bind(&pattern);
    }
    if !categories.is_empty() {
        trim.part(&mut query, "AND", "").push("category IN ");
        if !categories.is_empty() {
            query.push(" (");
            for (i, c) in categories.iter().copied().enumerate() {
                if i > 0 {
                    query.push(", ");
                }
                query.push_bind(c.name());
            }
            query.push(")");
        }
    }
    query.push(" ORDER BY ").push(order_by);
    query.build_query_as::<Item>().fetch_all(conn).await
}

/// `<insert id="insertAll">`
pub async fn insert_all(conn: &mut PgConnection, items: &[Item]) -> sqlx::Result<i32> {
    let mut query = QueryBuilder::<Postgres>::new(
        "INSERT INTO items (name, category, price, note) VALUES",
    );
    for (i, item) in items.iter().enumerate() {
        if i > 0 {
            query.push(",");
        }
        query
            .push(" (")
            .push_bind(&item.name)
            .push(", ")
            .push_bind(item.category.name())
            .push(", ")
            .push_bind(item.price)
            .push(", ")
            .push_bind(&item.note)
            .push(")");
    }
    let result = query.build().execute(conn).await?;
    Ok(result.rows_affected() as i32)
}

/// `<update id="updateSelective">`
pub async fn update_selective(conn: &mut PgConnection, item: &Item) -> sqlx::Result<i32> {
    let mut query = QueryBuilder::<Postgres>::new("UPDATE items");
    let mut trim = Trim::new("SET", "");
    if item.note.is_some() {
        trim.part(&mut query, "", ",").push("note = ").push_bind(&item.note);
    }
    trim
        .part(&mut query, "", ",")
        .push("name = ")
        .push_bind(&item.name)
        .push(", price = ")
        .push_bind(item.price);
    query.push(" WHERE id = ").push_bind(item.id);
    let result = query.build().execute(conn).await?;
    Ok(result.rows_affected() as i32)
}

/// `@Select`
pub async fn find_by_category(
    conn: &mut PgConnection,
    category: Option<Category>,
) -> sqlx::Result<Vec<Item>> {
    let mut query = QueryBuilder::<Postgres>::new(
        "SELECT id AS item_id, name, category, price, note FROM items",
    );
    let mut where_clause = Trim::new("WHERE", "");
    if category.is_some() {
        where_clause
            .part(&mut query, "", "")
            .push("category = ")
            .push_bind(category.map(Category::name));
    }
    query.push(" ORDER BY id");
    query.build().try_map(item_row).fetch_all(conn).await
}

/// `@Select`
pub async fn find_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Item>> {
    sqlx::query("SELECT id AS item_id, name, category, price, note FROM items WHERE id = $1")
        .bind(id)
        .try_map(item_row)
        .fetch_optional(conn)
        .await
}

/// `@Select`
pub async fn category_of(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Category>> {
    sqlx::query_scalar::<_, Category>("SELECT category FROM items WHERE id = $1")
        .bind(id)
        .fetch_optional(conn)
        .await
}

/// `<resultMap id="itemMap">` の行の対応。
fn item_map(row: PgRow) -> sqlx::Result<Item> {
    let mut item = Item::default();
    if let Some(v) = column(&row, "item_id")? {
        item.id = v;
    }
    if let Some(v) = column(&row, "item_name")? {
        item.name = v;
    }
    if let Some(v) = column(&row, "category")? {
        item.category = v;
    }
    if let Some(v) = column(&row, "price")? {
        item.price = v;
    }
    if let Some(v) = column(&row, "note")? {
        item.note = v;
    }
    Ok(item)
}

/// `@Results` の行の対応。
fn item_row(row: PgRow) -> sqlx::Result<Item> {
    let mut item = Item::default();
    if let Some(v) = column(&row, "item_id")? {
        item.id = v;
    }
    if let Some(v) = column(&row, "name")? {
        item.name = v;
    }
    if let Some(v) = column(&row, "category")? {
        item.category = v;
    }
    if let Some(v) = column(&row, "price")? {
        item.price = v;
    }
    if let Some(v) = column(&row, "note")? {
        item.note = v;
    }
    Ok(item)
}
