package com.theredpixelteam.kraitudao.interpreter;

import com.theredpixelteam.kraitudao.annotations.*;
import com.theredpixelteam.kraitudao.annotations.expandable.*;
import com.theredpixelteam.kraitudao.dataobject.*;
import com.theredpixelteam.redtea.predication.MultiCondition;
import com.theredpixelteam.redtea.predication.MultiPredicate;
import com.theredpixelteam.redtea.predication.NamedPredicate;
import com.theredpixelteam.redtea.util.Reference;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("unchecked")
public class SimpleDataObjectInterpreter implements DataObjectInterpreter {
    @Override
    public DataObject get(Class<?> type) throws DataObjectInterpretationException
    {
        Objects.requireNonNull(type, "type");

        int i = (type.getAnnotation(Unique.class) == null ? 0b00 : 0b01)
                | (type.getAnnotation(Multiple.class) == null ? 0b00 : 0b10);

        switch(i)
        {
            case 0b00: // not annotated
                throw new DataObjectInterpretationException("Metadata annotation not found in type: " + type.getCanonicalName());

            case 0b01: // only annotated by @Unique
                return getUnique0(type);

            case 0b10: // only annotated by @Multiple
                return getMultiple0(type);

            case 0b11: // both annotated
                throw new DataObjectInterpretationException("Duplicated metadata annotation in type: " + type.getCanonicalName());
        }

        throw new IllegalStateException("Should not reach here");
    }

    @Override
    public MultipleDataObject getMultiple(Class<?> type) throws DataObjectInterpretationException
    {
        Objects.requireNonNull(type, "type");

        if(type.getAnnotation(Multiple.class) == null)
            throw new DataObjectInterpretationException("Type: " + type.getCanonicalName() + " is not annotated by @Multiple");

        return getMultiple0(type);
    }

    @Override
    public UniqueDataObject getUnique(Class<?> type) throws DataObjectInterpretationException
    {
        Objects.requireNonNull(type, "type");

        if(type.getAnnotation(Unique.class) == null)
            throw new DataObjectInterpretationException("Type: " + type.getCanonicalName() + " is not annotated by @Unique");

        return getUnique0(type);
    }

    private MultipleDataObject getMultiple0(Class<?> type) throws DataObjectInterpretationException
    {
        MultipleDataObjectContainer container = new MultipleDataObjectContainer(type);

        parse(container);

        return container;
    }

    private UniqueDataObject getUnique0(Class<?> type) throws DataObjectInterpretationException
    {
        UniqueDataObjectContainer container = new UniqueDataObjectContainer(type);

        parse(container);

        return container;
    }

    private void parse(DataObjectContainer container) throws DataObjectInterpretationException
    {

    }

    private void parseFields(DataObjectContainer container)
    {
        for(Field field : container.getClass().getDeclaredFields())
        {
            MultiCondition.CurrentCondition<Class<?>> current;

            current = annotationCondition.apply(
                    field,
                    Key.class,
                    PrimaryKey.class,
                    SecondaryKey.class
            );

            Reference<KeyType> keyType = new Reference<>();
            ValueObjectContainer valueObject = new ValueObjectContainer(container.getType(), field.getType());
            valueObject.owner = container;

            if(current.getCurrent().trueCount() != 0)
            {
                Reference<String> name = new Reference<>();

                current.completelyOnlyIf(Key.class)
                        .perform(() -> {
                            valueObject.primaryKey = true;
                            keyType.set(KeyType.UNIQUE);
                            name.set(field.getAnnotation(Key.class).value());
                        })
                        .elseCompletelyOnlyIf(PrimaryKey.class)
                        .perform(() -> {
                            valueObject.primaryKey = true;
                            keyType.set(KeyType.PRIMARY);
                            name.set(field.getAnnotation(PrimaryKey.class).value());
                        })
                        .elseCompletelyOnlyIf(SecondaryKey.class)
                        .perform(() -> {
                            valueObject.secondaryKey = true;
                            keyType.set(KeyType.SECONDARY);
                            name.set(field.getAnnotation(SecondaryKey.class).value());
                        })
                        .orElse((predicate) -> {
                            if (predicate.trueCount() != 0)
                                throw new DataObjectMalformationException("Duplicated value object metadata");
                        });

                valueObject.name = name.get();
            }

            // TODO getter, setter, expand rules
            field.setAccessible(true);

            valueObject.getter = (obj) -> {
                try {
                    return field.get(obj);
                } catch (Exception e) {
                    throw new DataObjectError("Reflection error", e);
                }
            };

            valueObject.setter = (obj, val) -> {
                try {
                    field.set(obj, val);
                } catch (Exception e) {
                    throw new DataObjectError("Reflection error", e);
                }
            };



            valueObject.seal();

            if(valueObject.isKey())
                container.putKey(keyType.get(), valueObject);
            else
                container.putValue(valueObject);
        }
    }

    static NamedPredicate<AnnotatedElement, Class<?>> a(Class<? extends Annotation> annotation)
    {
        return NamedPredicate.of(annotation, (t) -> t.getAnnotation(annotation) != null);
    }

    private static final MultiCondition<AnnotatedElement, Class<?>> annotationCondition =
            MultiCondition.of(MultiPredicate.<AnnotatedElement, Class<?>>builder()
                    .next(a(Unique.class))
                    .next(a(Multiple.class))
                    .next(a(Key.class))
                    .next(a(PrimaryKey.class))
                    .next(a(SecondaryKey.class))
                    .next(a(Value.class))
                    .next(a(Getter.class))
                    .next(a(Setter.class))
                    .next(a(Inheritance.class))
//                  .next(a(BuiltinExpandRule.class))
//                  .next(a(CustomExpandRule.class))
//                  .next(a(ExpandableValue.class))
            .build());

    private static interface DataObjectContainer extends DataObject
    {
        void putKey(KeyType type, ValueObject valueObject);

        void putValue(ValueObject valueObject);

        void seal(); // except internal update

        boolean sealed();

        default void checkSeal()
        {
            if(sealed())
                throw new IllegalStateException("Already sealed");
        }
    }

    private static class UniqueDataObjectContainer implements UniqueDataObject, DataObjectContainer
    {
        UniqueDataObjectContainer(Class<?> type)
        {
            this.values = new HashMap<>();
            this.type = type;
        }

        @Override
        public void seal()
        {
            this.checkSeal();

            if(this.key == null)
                throw new DataObjectMalformationException("Key not defined");

            this.values = Collections.unmodifiableMap(values);

            this.sealed = true;
        }

        @Override
        public boolean sealed()
        {
            return sealed;
        }

        @Override
        public ValueObject getKey()
        {
            return key;
        }

        @Override
        public Optional<ValueObject> getValue(String name)
        {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public Map<String, ValueObject> getValues()
        {
            return values;
        }

        @Override
        public Class<?> getType()
        {
            return type;
        }

        @Override
        public void putKey(KeyType type, ValueObject valueObject)
        {
            this.checkSeal();

            if(!type.equals(KeyType.PRIMARY))
                throw new DataObjectMalformationException("@PrimaryKey and @SecondaryKey is not supported in unique data object");

            if(key != null)
                throw new DataObjectMalformationException("Primary key already exists");

            this.key = valueObject;
        }

        @Override
        public void putValue(ValueObject valueObject)
        {
            this.checkSeal();

            if(key.getName().equals(valueObject.getName()) || (values.putIfAbsent(valueObject.getName(), valueObject) != null))
                throw new DataObjectMalformationException("Duplicated value object: " + valueObject.getName());
        }

        final Class<?> type;

        ValueObject key;

        Map<String, ValueObject> values;

        private boolean sealed;
    }

    private static class MultipleDataObjectContainer implements MultipleDataObject, DataObjectContainer
    {
        MultipleDataObjectContainer(Class<?> type)
        {
            this.type = type;
            this.secondaryKeys = new HashMap<>();
        }

        @Override
        public void putKey(KeyType type, ValueObject valueObject)
        {
            this.checkSeal();

            switch(type)
            {
                case UNIQUE:
                    throw new DataObjectMalformationException("@Key is not supported in multiple data object (Or you/they should use @PrimaryKey maybe?)");

                case PRIMARY:
                    if(this.primaryKey != null)
                        throw new DataObjectMalformationException("Duplicated primary key");

                case SECONDARY:
                    if((secondaryKeys.putIfAbsent(valueObject.getName(), valueObject)) != null)
                        throw new DataObjectMalformationException("Duplicated secondary key: " + valueObject.getName());
            }
        }

        @Override
        public void putValue(ValueObject valueObject)
        {
            this.checkSeal();

            if((values.putIfAbsent(valueObject.getName(), valueObject)) != null)
                throw new DataObjectMalformationException("Duplicated value object: " + valueObject.getName());
        }

        @Override
        public void seal()
        {
            this.checkSeal();

            if(this.primaryKey == null)
                throw new DataObjectMalformationException("Primary Key not defined");

            this.secondaryKeys = Collections.unmodifiableMap(secondaryKeys);
            this.values = Collections.unmodifiableMap(values);

            this.sealed = true;
        }

        @Override
        public boolean sealed()
        {
            return sealed;
        }

        @Override
        public ValueObject getPrimaryKey()
        {
            return primaryKey;
        }

        @Override
        public Map<String, ValueObject> getSecondaryKeys()
        {
            return secondaryKeys;
        }

        @Override
        public Optional<ValueObject> getValue(String name)
        {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public Map<String, ValueObject> getValues()
        {
            return values;
        }

        @Override
        public Class<?> getType()
        {
            return type;
        }

        final Class<?> type;

        ValueObject primaryKey;

        Map<String, ValueObject> secondaryKeys;

        Map<String, ValueObject> values;

        private boolean sealed;
    }

    private static class ValueObjectContainer implements ValueObject
    {
        ValueObjectContainer(Class<?> owner, Class<?> type)
        {
            this.ownerType = owner;
            this.type = type;
        }

        void seal()
        {
            if(this.sealed)
                throw new IllegalStateException();

            if(name == null)
                throw new DataObjectMalformationException("Value name undefined");

            if(owner == null)
                throw new DataObjectMalformationException("null owner");

            if(getter == null)
                throw new DataObjectMalformationException("null getter");

            if(setter == null)
                throw new DataObjectMalformationException("null setter");

            this.sealed = true;
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public Class<?> getType()
        {
            return this.type;
        }

        @Override
        public Object get(Object object)
        {
            Objects.requireNonNull(object, "object");

            return get0(object);
        }

        @Override
        public <T> T get(Object object, Class<T> type)
        {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(type, "type");

            if(!this.ownerType.isInstance(object))
                throw DataObjectException.IncapableObject(object, this.ownerType);

            Object returned = get(object);

            if(returned == null)
                return null;

            if(!(type.isInstance(object)))
                throw new DataObjectError(DataObjectException.IncapableType(returned.getClass(), type));

            return (T) object;
        }

        Object get0(Object object)
        {
            return getter.get(object);
        }

        @Override
        public void set(Object object, Object value)
        {
            set(object, value, (Class) type);
        }

        @Override
        public <T> void set(Object object, T value, Class<T> type)
        {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(type, "type");

            if(!this.ownerType.isInstance(object))
                throw DataObjectException.IncapableObject(object, this.ownerType);

            if(!type.isInstance(value))
                throw DataObjectException.IncapableValue(value, type);

            set0(object, value);
        }

        void set0(Object object, Object value)
        {
            setter.set(object, value);
        }

        @Override
        public Class<?> getOwnerType()
        {
            return ownerType;
        }

        @Override
        public DataObject getOwner()
        {
            return owner;
        }

        @Override
        public boolean isPrimaryKey()
        {
            return primaryKey;
        }

        @Override
        public boolean isSecondaryKey()
        {
            return secondaryKey;
        }

        @Override
        public Optional<ExpandRule> getExpandRule()
        {
            return Optional.ofNullable(this.expandRule);
        }

        final Class<?> ownerType;

        String name;

        final Class<?> type;

        boolean primaryKey;

        boolean secondaryKey;

        DataObject owner;

        ExpandRule expandRule;

        Getter getter;

        Setter setter;

        private boolean sealed;

        private static interface Getter
        {
            Object get(Object object);
        }

        private static interface Setter
        {
            void set(Object object, Object value);
        }
    }

    private static class ExpandRuleContainer implements ExpandRule
    {
        ExpandRuleContainer(Class<?> type)
        {
            this.type = type;
        }

        void seal()
        {
            if(this.sealed)
                throw new IllegalStateException();

            this.entryList.toArray(this.entries = new Entry[this.entryList.size()]);

            this.sealed = true;
        }

        @Override
        public Class<?> getExpandingType()
        {
            return this.type;
        }

        @Override
        public Entry[] getEntries()
        {
            return Arrays.copyOf(entries, entries.length);
        }

        ArrayList<Entry> entryList = new ArrayList<>();

        final Class<?> type;

        private Entry[] entries;

        private boolean sealed;
    }

    private static class EntryContainer implements ExpandRule.Entry
    {
        EntryContainer(String name, Class<?> expandedType)
        {
            this.name = name;
            this.expandedType = expandedType;
        }

        void seal()
        {
            if(this.sealed)
                throw new IllegalStateException();

            if(this.getterInfo == null)
                throw new DataObjectMalformationException("Getter info undefined");

            if(this.setterInfo == null)
                throw new DataObjectMalformationException("Setter info undefined");

            this.sealed = true;
        }

        @Override
        public String name()
        {
            return this.name;
        }

        @Override
        public ExpandRule.At getterInfo()
        {
            return this.getterInfo;
        }

        @Override
        public ExpandRule.At setterInfo()
        {
            return this.setterInfo;
        }

        @Override
        public Class<?> getExpandedType()
        {
            return this.expandedType;
        }

        ExpandRule.At getterInfo;

        ExpandRule.At setterInfo;

        final Class<?> expandedType;

        final String name;

        private boolean sealed;
    }

    private static enum KeyType
    {
        UNIQUE,
        PRIMARY,
        SECONDARY
    }
}
