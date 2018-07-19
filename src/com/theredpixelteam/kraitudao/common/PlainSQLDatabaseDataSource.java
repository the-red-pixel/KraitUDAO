/*
 * PlainSQLDatabaseDataSource.java
 *
 * Copyright (C) 2018 The Red Pixel <theredpixelteam.com>
 * Copyright (C) 2018 KuCrO3 Studio <kucro3.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

package com.theredpixelteam.kraitudao.common;

import com.theredpixelteam.kraitudao.DataSource;
import com.theredpixelteam.kraitudao.DataSourceException;
import com.theredpixelteam.kraitudao.ObjectConstructor;
import com.theredpixelteam.kraitudao.Transaction;
import com.theredpixelteam.kraitudao.annotations.Element;
import com.theredpixelteam.kraitudao.annotations.metadata.common.ExpandForcibly;
import com.theredpixelteam.kraitudao.annotations.metadata.common.NotNull;
import com.theredpixelteam.kraitudao.common.sql.*;
import com.theredpixelteam.kraitudao.dataobject.*;
import com.theredpixelteam.kraitudao.dataobject.util.ValueObjectIterator;
import com.theredpixelteam.kraitudao.interpreter.DataObjectExpander;
import com.theredpixelteam.kraitudao.interpreter.DataObjectInterpretationException;
import com.theredpixelteam.kraitudao.interpreter.DataObjectInterpreter;
import com.theredpixelteam.kraitudao.interpreter.DataObjectMalformationException;
import com.theredpixelteam.kraitudao.interpreter.common.StandardDataObjectExpander;
import com.theredpixelteam.kraitudao.interpreter.common.StandardDataObjectInterpreter;
import com.theredpixelteam.redtea.function.SupplierWithThrowable;
import com.theredpixelteam.redtea.util.Pair;
import com.theredpixelteam.redtea.util.Vector3;
import com.theredpixelteam.redtea.util.concurrent.Increment;
import com.theredpixelteam.redtea.util.Optional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@SuppressWarnings("unchecked")
public class PlainSQLDatabaseDataSource implements DataSource {
    public PlainSQLDatabaseDataSource(Connection connection,
                                      String tableName,
                                      DataObjectInterpreter interpreter,
                                      DataObjectExpander expander,
                                      DataObjectContainer container,
                                      DatabaseManipulator databaseManipulator,
                                      DataArgumentWrapper argumentWrapper,
                                      DataExtractorFactory extractorFactory)
            throws DataSourceException
    {
        this.connection = connection;
        this.tableName = tableName;
        this.interpreter = interpreter;
        this.expander = expander;
        this.container = container;
        this.manipulator = databaseManipulator;
        this.argumentWrapper = argumentWrapper;
        this.extractorFactory = extractorFactory;

        try {
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DataSourceException(e);
        }
    }

    public PlainSQLDatabaseDataSource(Connection connection,
                                      String tableName,
                                      DataObjectInterpreter interpreter,
                                      DataObjectExpander expander,
                                      DataObjectContainer container,
                                      DatabaseManipulator databaseManipulator)
            throws DataSourceException
    {
        this(connection, tableName, interpreter, expander, container, databaseManipulator, DefaultDataArgumentWrapper.INSTANCE, DefaultDataExtractorFactory.INSTANCE);
    }

    public PlainSQLDatabaseDataSource(Connection connection,
                                      String tableName,
                                      DataObjectInterpreter interpreter,
                                      DataObjectExpander expander,
                                      DataObjectContainer container)
            throws DataSourceException
    {
        this(connection, tableName, interpreter, expander, container, DefaultDatabaseManipulator.INSTANCE);
    }

    public PlainSQLDatabaseDataSource(Connection connection,
                                      String tableName,
                                      DataObjectInterpreter interpreter,
                                      DataObjectExpander expander)
            throws DataSourceException
    {
        this(connection, tableName, interpreter, expander, DataObjectCache.getGlobal());
    }

    public PlainSQLDatabaseDataSource(Connection connection,
                                      String tableName)
            throws DataSourceException
    {
        this(connection, tableName, StandardDataObjectInterpreter.INSTANCE, StandardDataObjectExpander.INSTANCE);
    }

    public Connection getConnection()
    {
        return this.connection;
    }

    public String getTableName()
    {
        return this.tableName;
    }

    private void checkTransaction(Transaction transaction) throws DataSourceException
    {
        if((transaction == null && this.currentTransaction != null)
                || (transaction != null && !transaction.equals(this.currentTransaction)))
            throw new DataSourceException.Busy();
    }

    private void extract(ResultSet resultSet, Object object, ValueObject valueObject, String tableName, Class<?>[] signatured, Increment signaturePointer)
            throws DataSourceException
    {
        switch (valueObject.getStructure())
        {
            case VALUE:
                extractValue(resultSet, object, valueObject, new StringBuilder());
                break;

            case MAP:

                break;

            case SET:

                break;

            case LIST:

                break;

            default:
                throw new Error("Should not reach here");
        }
    }

    private <T> void extractValue(ResultSet resultSet, Object object, ValueObject valueObject, StringBuilder prefix) throws DataSourceException
    {
        Class<T> dataType = (Class<T>) valueObject.getType();

        boolean supported = manipulator.supportType(dataType);
        boolean expandForcibly = valueObject.hasMetadata(ExpandForcibly.class);

        boolean expanding = expandForcibly || !supported;

        EXPANDABLE_OBJECT_CONSTRUCTION:
        if (expanding)
        {
            if (valueObject.get(object) != null)
                break EXPANDABLE_OBJECT_CONSTRUCTION;

            ObjectConstructor<T> objectConstructor = valueObject.getConstructor(dataType)
                    .throwIfEmpty(
                            () -> new DataSourceException(
                                    new DataObjectMalformationException("Bad constructor type of value object \"" + valueObject.getName() + "\"")))
                    .getWhenNull(() -> ObjectConstructor.ofDefault(dataType));

            try {
                T constructed = objectConstructor.newInstance(object);

                valueObject.set(object, constructed);
            } catch (Exception e) {
                throw new DataSourceException("Construction failure", e);
            }
        }

        boolean elementAnnotated = valueObject.getType().getAnnotation(Element.class) != null;

        if (elementAnnotated) try // EXPAND_ELEMENT_VALUE_OBJECT
        {
            DataObject dataObject = container.interpretIfAbsent(dataType, interpreter);

            if (!(dataObject instanceof ElementDataObject))
                throw new DataSourceException.UnsupportedValueType(dataType.getCanonicalName());

            ElementDataObject elementDataObject = (ElementDataObject) dataObject;

            for (ValueObject elementValueObject : elementDataObject.getValues().values())
                extractValue(resultSet, object, elementValueObject, prefix.append(valueObject.getName()).append("_"));
        } catch (DataObjectInterpretationException e) {
            throw new DataSourceException(e);
        }
        else if (expanding) try // EXPAND_EXPANDABLE_VALUE_OBJECT
        {
            Map<String, ValueObject> expanded = container.expand(valueObject, expander)
                    .orElseThrow(() -> new DataSourceException.UnsupportedValueType(dataType.getCanonicalName()));

            for (ValueObject expandedValueObject : expanded.values())
                extractValue(resultSet, object, expandedValueObject, prefix.append(valueObject.getName()).append("_"));
        } catch (DataObjectInterpretationException e) {
            throw new DataSourceException(e);
        }
    }

    @Override
    public <T> boolean pull(T object, Class<T> type) throws DataSourceException
    {
        try {
            DataObject dataObject = container.interpretIfAbsent(type, interpreter);

            if (dataObject instanceof ElementDataObject)
            {
                Set<String> valueSet = dataObject.getValues().keySet();
                String[] values = valueSet.toArray(new String[valueSet.size()]);

                try (ResultSet resultSet = manipulator.query(connection, tableName, null, values)) {
                    for (ValueObject valueObject : dataObject.getValues().values())
                        extract(resultSet, object, valueObject, this.tableName, null, null);
                } catch (SQLException e) {
                    throw new DataSourceException("SQLException", e);
                }

                return true;
            }

            return false;
        } catch (DataObjectInterpretationException e) {
            throw new DataSourceException(e);
        }
    }

    @Override
    public <T> boolean pull(T object, Class<T> type, Class<?>... signatured) throws DataSourceException
    {
        return false;
    }

    @Override
    public <T, X extends Throwable> Collection<T> pull(Class<T> type, SupplierWithThrowable<T, X> constructor) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T, X extends Throwable> Collection<T> pull(Class<T> type, SupplierWithThrowable<T, X> constructor, Class<?>... signatured)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public <T, X extends Throwable> Collection<T> pullVaguely(T object, Class<T> type, SupplierWithThrowable<T, X> constructor)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction commit(Transaction transaction, T object, Class<T> type)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction commit(Transaction transaction, T object, Class<T> type, Class<?>... signatured)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction remove(Transaction transaction, T object, Class<T> type)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public Transaction clear(Transaction transaction) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction removeVaguely(Transaction transaction, T object, Class<T> type)
            throws DataSourceException
    {
        return null;
    }

    @Override
    public void waitForTransaction()
    {
        while(this.currentTransaction != null);
    }

    public void createTable(Connection conection, Class<?> dataType) throws DataSourceException
    {
        try {
            createTable0(connection, container.interpretIfAbsent(dataType, interpreter), false);
        } catch (DataObjectInterpretationException e) {
            throw new DataSourceException(e);
        }
    }

    public void createTable(Connection connection, DataObject dataObject) throws DataSourceException
    {
        createTable0(connection, dataObject, false);
    }

    public boolean createTableIfNotExists(Connection connection, Class<?> dataType) throws DataSourceException
    {
        try {
            return createTable0(connection, container.interpretIfAbsent(dataType, interpreter), true);
        } catch (DataObjectInterpretationException e) {
            throw new DataSourceException(e);
        }
    }

    public boolean createTableIfNotExists(Connection connection, DataObject dataObject) throws DataSourceException
    {
        return createTable0(connection, dataObject, true);
    }

    private boolean createTable0(Connection connection, DataObject dataObject, boolean ifNotExists) throws DataSourceException
    {
        return createTable0(connection, tableName, dataObject, ifNotExists);
    }

    @SuppressWarnings("unchecked")
    private boolean createTable0(Connection connection, String tableName, DataObject dataObject, boolean ifNotExists) throws DataSourceException
    {
        try {
            List<Constraint> tableConstraints = new ArrayList<>();
            List<Vector3<String, Class<?>, Constraint[]>> columns = new ArrayList<>();

            List<ValueObject> keys = new ArrayList<>();

            List<ValueObject> valueObjects = new ArrayList<>();
            for (ValueObject valueObject : new ValueObjectIterator(dataObject))
            {
                Class<?> columnType = tryRemapping(valueObject.getType());

                if (valueObject.hasMetadata(ExpandForcibly.class) || !manipulator.supportType(columnType))
                    valueObjects.addAll(container.expand(valueObject, expander)
                            .orElseThrow(() -> new DataSourceException.UnsupportedValueType(columnType.getCanonicalName())).values());
                else
                    valueObjects.add(valueObject);

                for (ValueObject confirmed : valueObjects)
                {
                    if(confirmed.isKey())
                        keys.add(confirmed);

                    columns.add(Vector3.of(
                            confirmed.getName(),
                            tryRemapping(confirmed.getType()),
                            confirmed.hasMetadata(NotNull.class) ? new Constraint[]{Constraint.ofNotNull()} : new Constraint[0]));
                }

                valueObjects.clear();
            }

            if(!keys.isEmpty())
            {
                String[] keyNames = new String[keys.size()];

                for(int i = 0; i < keyNames.length; i++)
                    keyNames[i] = keys.get(i).getName();

                tableConstraints.add(Constraint.ofPrimaryKey(keyNames));
            }

            Constraint[] tableConstraintArray = tableConstraints.toArray(new Constraint[tableConstraints.size()]);
            Vector3<String, Class<?>, Constraint[]>[] columnArray = columns.toArray(new Vector3[columns.size()]);

            if(ifNotExists)
                return manipulator.createTableIfNotExists(connection, tableName, columnArray, tableConstraintArray);
            else
                manipulator.createTable(connection, tableName, columnArray, tableConstraintArray);
        } catch (DataObjectInterpretationException | SQLException e) {
            throw new DataSourceException(e);
        }

        return true;
    }

    public DatabaseManipulator getManipulator()
    {
        return manipulator;
    }

    public void setManipulator(DatabaseManipulator manipulator)
    {
        this.manipulator = Objects.requireNonNull(manipulator);
    }

    public DataArgumentWrapper getArgumentWrapper()
    {
        return argumentWrapper;
    }

    public void setArgumentWrapper(DataArgumentWrapper argumentWrapper)
    {
        this.argumentWrapper = Objects.requireNonNull(argumentWrapper);
    }

    public void setExtractorFactory(DataExtractorFactory extractorFactory)
    {
        this.extractorFactory = Objects.requireNonNull(extractorFactory);
    }

    public DataExtractorFactory getExtractorFactory()
    {
        return extractorFactory;
    }

    private static Class<?> tryRemapping(Class<?> type)
    {
        Class<?> remapped = REMAPPED.get(type);

        return remapped == null ? type : remapped;
    }

    private volatile Transaction currentTransaction;

    protected String tableName;

    protected Connection connection;

    protected DataObjectInterpreter interpreter;

    protected DataObjectExpander expander;

    protected final DataObjectContainer container;

    protected DatabaseManipulator manipulator;

    protected DataArgumentWrapper argumentWrapper;

    protected DataExtractorFactory extractorFactory;

    private static final Map<Class<?>, Class<?>> REMAPPED = new HashMap<Class<?>, Class<?>>() {
        {
            put(Map.class,          String.class);
            put(List.class,         String.class);
            put(Set.class,          String.class);
//          put(Hashtable.class,    String.class); actually Map
//          put(Vector.class,       String.class); actually List
        }
    };

    private class TransactionImpl implements Transaction
    {
        TransactionImpl()
        {
        }

        @Override
        public boolean push() throws DataSourceException
        {
            if(!valid)
                return false;

            try {
                connection.commit();
            } catch (SQLException e) {
                throw new DataSourceException(e);
            }

            destroy();
            return true;
        }

        @Override
        public boolean cancel()
        {
            if(!valid)
                return false;

            try {
                connection.rollback();
            } catch (SQLException e) {
                this.lastException = new DataSourceException(e);
                return false;
            }

            destroy();
            return true;
        }

        @Override
        public Optional<Exception> getLastException()
        {
            return Optional.ofNullable(this.lastException);
        }

        void destroy()
        {
            this.valid = false;
            currentTransaction = null;
        }

        boolean valid()
        {
            return valid;
        }

        private Exception lastException;

        private boolean valid = true;
    }

    private static class KeyInjection
    {
        KeyInjection()
        {
        }

        void inject(Object object)
        {
            for(Pair<ValueObject, Object> entry : injectiveElements)
                entry.first().set(object, entry.second());
        }

        private final List<Pair<ValueObject, Object>> injectiveElements = new ArrayList<>();
    }
}
