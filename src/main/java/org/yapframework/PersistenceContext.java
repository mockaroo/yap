package org.yapframework;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.yapframework.exceptions.InvalidModelTypeException;
import org.yapframework.metadata.*;

import javax.sql.DataSource;
import java.util.*;

import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * Persistence configuration
 */
public class PersistenceContext {
    private SQLDialect dialect;
    private DataSource dataSource;
    private Map<String, ModelMetaData> configuration = new HashMap<String, ModelMetaData>();
    private DSLContext jooq;

    public PersistenceContext configure(ModelMetaData md) {
        configuration.put(md.getType(), md);
        return this;
    }

    public PersistenceContext init() {
        jooq = DSL.using(dataSource, dialect);
        return this;
    }

    public PersistenceContext setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public PersistenceContext setDialect(SQLDialect dialect) {
        this.dialect = dialect;
        return this;
    }

    public DSLContext getJooq() {
        return jooq;
    }

    public ModelMetaData metaDataFor(String type) {
        return configuration.get(type);
    }

    /**
     * Creates a new unsaved model by type.
     * @param type A configured model type
     * @return
     */
    public Model create(String type) {
        return new Model(metaDataFor(type), this);
    }

    /**
     * Finds a single model instance by id
     * @param type The model type
     * @param id The id
     * @return
     */
    public Model find(String type, Object id) {
        ModelMetaData md = metaDataFor(type);

        if(md == null) {
            throw new InvalidModelTypeException("Model type \"" + type + "\" not found.  Did you forget to configure this type in the PersistenceContext?");
        }

        Record record = jooq.select()
                .from(md.getTable())
                .where(field(md.getPrimaryKey()).equal(id))
                .fetchOne();

        return record == null ? null : new Model(record.intoMap(), md, this);
    }

    /**
     * Saves a record, doing and insert or updated where appropriate.
     * @param model
     */
    public void save(Model model) {
        save(model, null, null);
    }

    /**
     * Deletes a model.
     * @param model
     */
    public void delete(Model model) {
        ModelMetaData md = model.getMetaData();
        jooq.delete(table(md.getTable()))
                .where(field(md.getPrimaryKey()).equal(model.getValues().get(md.getPrimaryKey())))
                .execute();
    }

    /**
     * Fetches a field's value (either a model property or relationship).
     * @param model The owner model
     * @param fieldName The field to fetch
     * @param retClass The class of value to return
     * @param <T> The class of value to return
     * @return
     */
    public <T> T fetch(Model model, String fieldName, Class<T> retClass) {
        ModelMetaData metaData = model.getMetaData();

        /*
         * If fieldName is not found as a key in the value map, it must be a lazy-loaded relationship
         * that hasn't been fetched yet
         */
        Relationship rel = metaData.relationshipFor(fieldName);

        if(rel instanceof HasMany) {
            return (T) fetchHasMany((HasMany) rel, model.getId());
        } else if(rel instanceof HasAndBelongsToMany) {
            return (T) fetchHasAndBelongsToMany((HasAndBelongsToMany) rel, model.getId());
        } else if(rel instanceof BelongsTo) {
            return (T) fetchBelongsTo((BelongsTo) rel, model.getValues().get(rel.getColumn()));
        } else {
            throw new UnsupportedOperationException("Unsupported relationship type " + rel.getClass().getName());
        }
    }

    // Begin private methods

    private void save(Model model, HasMany relationship, Object foreignKeyValue) {
        if(model.getId() == null) {
            insert(model, relationship, foreignKeyValue);
        } else {
            update(model, relationship, foreignKeyValue);
        }
    }

    /**
     * Inserts a new record
     * @param model
     */
    // TODO check for unsaved transient belongsTo
    private void insert(Model model, HasMany relationship, Object foreignKeyValue) {
        validate(model);
        ModelMetaData md = model.getMetaData();

        jooq.insertInto(table(md.getTable()))
                .set(toFieldValueMap(model, relationship, foreignKeyValue))
                .execute();

        // set generated id on newly saved record
        Record returned = jooq.select(getGeneratedKeyField()).fetchOne();
        String primaryKey = md.getPrimaryKey();
        model.set(primaryKey, returned.getValue(primaryKey, Integer.class));

        saveCollections(model);
    }

    /**
     * Updates an existing record.
     * @param model
     */
    private void update(Model model, HasMany relationship, Object foreignKeyValue) {
        validate(model);
        ModelMetaData md = model.getMetaData();

        jooq.update(table(md.getTable()))
                .set(toFieldValueMap(model, relationship, foreignKeyValue))
                .where(field(md.getPrimaryKey()).equal(model.getId())).execute();

        saveCollections(model);
    }

    /**
     * Gets a map of field to value for all model properties
     * @param model
     * @return
     */
    private Map<Field<?>, Object> toFieldValueMap(Model model, HasMany relationship, Object foreignKeyValue) {
        Map<Field<?>, Object> result = new HashMap<Field<?>, Object>();
        ModelMetaData md = model.getMetaData();
        String primaryKey = md.getPrimaryKey();

        for(Map.Entry<String, Object> entry:model.getValues().entrySet()) {
            String column = entry.getKey();
            Object value = entry.getValue();
            Relationship rel = md.relationshipFor(column);

            if(!column.equals(primaryKey) && !(rel instanceof CollectionRelationship)) {
                Field<Object> f;

                if(rel instanceof BelongsTo) {
                    f = field(rel.getColumn());
                    value = value == null ? null : ((Model) value).getId();
                } else {
                    f = field(column);
                }

                if(value == null) {
                    result.put(f, castNull(f));
                } else {
                    result.put(f, value);
                }
            }
        }

        // add order and foreign key value if saving as part of a has many
        if(relationship != null) {
            if(relationship.getOrderColumn() != null) {
                result.put(field(relationship.getOrderColumn()), model.getOrder());
            }

            Field<Object> f = field(relationship.getColumn());

            if(foreignKeyValue == null) {
                result.put(f, castNull(f));
            } else {
                result.put(f, foreignKeyValue);
            }
        }

        return result;
    }

    /**
     * Validates that a model can be saved.
     * @param model
     */
    private void validate(Model model) {
        Map<String, Object> values = model.getValues();

        if(values == null || values.isEmpty()) {
            throw new RuntimeException("Model must have at least one value to save");
        }
    }

    /**
     * Saves all collection relationships for a model
     * @param model
     */
    private void saveCollections(Model model) {
        ModelMetaData md = model.getMetaData();

        for(Relationship rel:md.getRelationships().values()) {
            if(rel instanceof HasMany) {
                save((HasMany) rel, model);
            } else if(rel instanceof HasAndBelongsToMany) {
                save((HasAndBelongsToMany) rel, model);
            }
        }
    }

    /**
     * Saves links for a HasMany relationship
     * @param rel The relationship
     * @param model The owner model
     */
    private void save(HasMany rel, Model model) {
        List<Model> items = model.get(rel.getName(), List.class);
        List<Object> idsToKeep = new LinkedList<Object>();
        ModelMetaData itemMetaData = metaDataFor(rel.getRelatedToType());

        if(items != null) {
            int order = 0;

            // cascade save all items in collection
            for(Iterator<Model> i = items.iterator(); i.hasNext();) {
                Model item = i.next();
                item.setOrder(order++);
                save(item, rel, model.getId());
                idsToKeep.add(item.getId());
            }

            // delete any records that weren't in the collection
            if(!idsToKeep.isEmpty()) {
                Result<Record> recordsToDelete = jooq.select()
                        .from(itemMetaData.getTable())
                        .where(field(itemMetaData.getPrimaryKey()).notIn(idsToKeep))
                        .fetch();

                for(Record r:recordsToDelete) {
                    if(rel.isDestroyOrphans()) {
                        Model item = new Model(r.intoMap(), itemMetaData, this);
                        delete(item);
                    } else {
                        save(model, rel, null);
                    }
                }
            }
        }
    }

    /**
     * Saves links for a HasAndBelongsToMany relationship
     * @param rel The relationship
     * @param model The owner model
     */
    private void save(HasAndBelongsToMany rel, Model model) {
        List<Model> items = model.get(rel.getName(), List.class);
        if(items == null) return;

        int order = 0;
        Record[] relations = fetchRelations(rel, model.getId());

        // save each position in the collection
        for(Iterator<Model> i = items.iterator(); i.hasNext();) {
            Model item = i.next();

            // look for a saved link to update by position
            Record link = relations.length > order ? relations[order] : null;

            if(link == null) {
                // insert a new position
                jooq.insertInto(table(rel.getTable()))
                        .set(field(rel.getForeignKeyColumn()), model.getId())
                        .set(field(rel.getColumn()), item.getId())
                        .set(field(rel.getOrderColumn()), order)
                        .execute();
            } else {
                // update the saved position
                link.setValue(field(rel.getColumn()), item.getId());
                jooq.update(table(rel.getTable())).set(link);
            }

            order++;
        }

        // delete positions greater than the highest position saved
        jooq.delete(table(rel.getTable()))
                .where(field(rel.getForeignKeyColumn()).equal(model.getId())
                        .and(field(rel.getOrderColumn()).ge(order)))
                .execute();
    }

    /**
     * Fetches the link table records for a HasAndBelongsToMany relationship
     * @param rel The relationship
     * @param id The id of the owner model
     * @return
     */
    private Record[] fetchRelations(HasAndBelongsToMany rel, Object id) {
        Result<Record> result = jooq.select()
                .from(rel.getTable())
                .where(field(rel.getForeignKeyColumn()).equal(id))
                .orderBy(field(rel.getOrderColumn()))
                .fetch();

        return result.toArray(new Record[result.size()]);
    }

    /**
     * Gets the field for retrieving a newly inserted id.
     * @return
     */
    private Field getGeneratedKeyField() {
        return field("LASTVAL()").as("id");
    }

    /**
     * Fetches the list of related models for a has many relationship
     * @param rel The relationship
     * @param foreignKeyValue The foreign key value on the related table
     * @return
     */
    private List<Model> fetchHasMany(HasMany rel, Object foreignKeyValue) {
        ModelMetaData related = metaDataFor(rel.getRelatedToType());

        SelectConditionStep<Record> select = jooq.select()
                .from(related.getTable())
                .where(field(rel.getColumn()).equal(foreignKeyValue));

        if(rel.getOrderColumn() != null) {
            select.orderBy(field(rel.getOrderColumn()));
        }

        Result<Record> results = select.fetch();

        List<Model> models = new LinkedList<Model>();

        for(Record record:results) {
            models.add(new Model(record.intoMap(), related, this));
        }

        return models;
    }

    /**
     * Fetches the model for a belongs to relationship
     * @param rel The relationship
     * @param foreignKeyValue The foreign key value on the model
     * @return
     */
    private Model fetchBelongsTo(BelongsTo rel, Object foreignKeyValue) {
        return foreignKeyValue == null ? null : find(rel.getRelatedToType(), foreignKeyValue);
    }

    /**
     * Fetches the list of models in a HasAndBelongsToMany relationship with the specified model
     * @param rel The relationship
     * @param id The owner model id
     * @return
     */
    private List<Model> fetchHasAndBelongsToMany(HasAndBelongsToMany rel, Object id) {
        Record[] relations = fetchRelations(rel, id);
        List<Model> models = new LinkedList<Model>();

        for(Record relation:relations) {
            models.add(find(rel.getRelatedToType(), relation.getValue(rel.getColumn())));
        }

        return models;
    }
}