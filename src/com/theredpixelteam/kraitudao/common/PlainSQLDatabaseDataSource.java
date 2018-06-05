package com.theredpixelteam.kraitudao.common;

import com.theredpixelteam.kraitudao.DataSource;
import com.theredpixelteam.kraitudao.DataSourceException;
import com.theredpixelteam.kraitudao.Transaction;
import com.theredpixelteam.kraitudao.annotations.metadata.common.NotNull;
import com.theredpixelteam.kraitudao.annotations.metadata.common.Precision;
import com.theredpixelteam.kraitudao.annotations.metadata.common.Size;
import com.theredpixelteam.kraitudao.dataobject.*;
import com.theredpixelteam.kraitudao.interpreter.DataObjectInterpretationException;
import com.theredpixelteam.kraitudao.interpreter.DataObjectInterpreter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 版权所有（C） The Red Pixel <theredpixelteam.com>
 * 版权所有（C)  KuCrO3 Studio
 * 这一程序是自由软件，你可以遵照自由软件基金会出版的GNU通用公共许可证条款
 * 来修改和重新发布这一程序。或者用许可证的第二版，或者（根据你的选择）用任
 * 何更新的版本。
 * 发布这一程序的目的是希望它有用，但没有任何担保。甚至没有适合特定目的的隐
 * 含的担保。更详细的情况请参阅GNU通用公共许可证。
 */
public class PlainSQLDatabaseDataSource implements DataSource {
    public PlainSQLDatabaseDataSource(Connection connection,
                                   String tableName,
                                   DataObjectInterpreter interpreter,
                                   DataObjectContainer container)
            throws DataSourceException
    {
        this.connection = connection;
        this.tableName = tableName;
        this.interpreter = interpreter;
        this.container = container;

        try {
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DataSourceException(e);
        }
    }

    public PlainSQLDatabaseDataSource(Connection connection,
                                   String tableName,
                                   DataObjectInterpreter interpreter)
            throws DataSourceException
    {
        this(connection, tableName, interpreter, DataObjectCache.getGlobal());
    }

    public Connection getConnection()
    {
        return this.connection;
    }

    public String getTableName()
    {
        return this.tableName;
    }

    @Override
    public <T> void pull(T object, Class<T> type) throws DataSourceException
    {

    }

    @Override
    public <T> Collection<T> pull(Class<T> type) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Collection<T> pullVaguely(T object) throws DataSourceException
    {
        return null;
    }

    @Override
    public Transaction commit(Transaction transition, Collection<Object> objects) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction commit(Transaction transition, T object, Class<T> type) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction remove(T object) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction remove(Transaction transition, T object) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction clear() throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction clear(Transaction transition) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction removeVaguely(T object) throws DataSourceException
    {
        return null;
    }

    @Override
    public <T> Transaction removeVaguely(Transaction transition, T object) throws DataSourceException
    {
        return null;
    }

    private void checkTransaction(Transaction transaction)
    {

    }

    @Override
    public void waitForTransaction()
    {
        while(this.currentTransaction != null);
    }

    public static Optional<String> getSQLType(Class<?> type)
    {
        return Optional.ofNullable(MAPPING.get(type));
    }

    public int createTable(Connection conection, String tableName, Class<?> dataType)
            throws SQLException, DataObjectInterpretationException
    {
        return createTable(conection, tableName, container.interpretIfAbsent(dataType, interpreter));
    }

    public static int createTable(Connection connection, String tableName, DataObject dataObject) throws SQLException
    {
        return createTable0(connection, "CREATE TABLE " + tableName, dataObject);
    }

    public int createTableIfNotExists(Connection connection, String tableName, Class<?> dataType)
            throws SQLException, DataObjectInterpretationException
    {
        return createTable(connection, tableName, container.interpretIfAbsent(dataType, interpreter));
    }

    public static int createTableIfNotExists(Connection connection, String tableName, DataObject dataObject) throws SQLException
    {
        return createTable0(connection, "CREATE TABLE IF NOT EXISTS " + tableName, dataObject);
    }

    private static int createTable0(Connection connection, String statement, DataObject dataObject) throws SQLException
    {
        StringBuilder stmt = new StringBuilder(statement).append(" (");
        StringBuilder constraint = new StringBuilder();

        if(dataObject instanceof UniqueDataObject)
        {
            UniqueDataObject uniqueDataObject = (UniqueDataObject) dataObject;
            ValueObject key = uniqueDataObject.getKey();

            appendTableElement(stmt, key);

            constraint.append(String.format("CONSTRAINT CONSTRAINT_KEY PRIMARY KEY (%s)",
                    key.getName()));
        }
        else if(dataObject instanceof MultipleDataObject)
        {
            MultipleDataObject multipleDataObject = (MultipleDataObject) dataObject;
            ValueObject primaryKey = multipleDataObject.getPrimaryKey();

            appendTableElement(stmt, primaryKey);


            constraint.append(
                            String.format("CONSTRAINT CONSTRAINT_PRIMARY_KEY PRIMARY KEY (%s)",
                                    primaryKey.getName()))
                    .append("CONSTRAINT CONSTRAINT_SECONDARY_KEYS SECONDARY KEY (");

            Collection<ValueObject> valueObjectCollection = multipleDataObject.getSecondaryKeys().values();
            int valueObjectCollectionSize = valueObjectCollection.size(), i = 0;
            for(ValueObject secondaryKey : multipleDataObject.getSecondaryKeys().values())
            {
                appendTableElement(stmt, secondaryKey);

                constraint.append(secondaryKey.getName());

                if(++i < valueObjectCollectionSize)
                    constraint.append(",");
            }

            constraint.append(")");
        }
        else
            throw new DataObjectException("Illegal or unsupported data object type");

        for(ValueObject valueObject : dataObject.getValues().values())
            appendTableElement(stmt, valueObject);

        stmt.append(constraint).append(")");

        System.out.println(stmt.toString());

        PreparedStatement preparedStatement = connection.prepareStatement(stmt.toString());
        int n = preparedStatement.executeUpdate();

        preparedStatement.close();

        return n;
    }

    private static void appendTableElement(StringBuilder stmt, ValueObject valueObject)
    {
        Class<?> type = tryToUnbox(valueObject.getType());
        String typeString = MAPPING.get(type);

        if(typeString == null)
            throw new DataObjectException("Unsupported type: " + type.getCanonicalName() + " (PLEASE Try to use expandable value)");

        TypeDecorator typeDecorator = TYPE_DECORATORS.get(type);
        if(typeDecorator != null)
            typeString = typeDecorator.decorate(typeString, valueObject);

        stmt.append(String.format(typeString, valueObject.getName())).append(",");
    }

    private static Class<?> tryToUnbox(Class<?> type)
    {
        Class<?> unboxed = BOXING.get(type);

        return unboxed == null ? type : unboxed;
    }

    private static TypeDecorator of(TypeDecorator... decorators)
    {
        final TypeDecorator[] sequence = decorators;
        return (type, valueObject) -> {
            for(TypeDecorator decorator : sequence)
                type = decorator.decorate(type, valueObject);

            return type;
        };
    }

    private volatile Transaction currentTransaction;

    protected String tableName;

    protected Connection connection;

    protected DataObjectInterpreter interpreter;

    protected final DataObjectContainer container;

    protected static final Map<Class<?>, DataObject> CACHE = new HashMap<>();

    private static final Map<Class<?>, Class<?>> BOXING = new HashMap<Class<?>, Class<?>>() {
        {
            //  Boxed type       |  Unboxed type
            put(Boolean.class,      boolean.class);
            put(Character.class,    char.class);
            put(Short.class,        short.class);
            put(Integer.class,      int.class);
            put(Long.class,         long.class);
            put(Float.class,        float.class);
            put(Double.class,       double.class);
        }
    };

    private static final Map<Class<?>, String> MAPPING = new HashMap<Class<?>, String>() {
        {
            //  Java type        |  SQL type
            put(boolean.class,      "BOOLEAN");
            put(byte.class,         "BINARY(1)");
            put(char.class,         "NCHAR(1)");
            put(short.class,        "SMALLINT");
            put(int.class,          "INTEGER");
            put(long.class,         "BIGINT");
            put(float.class,        "FLOAT");
            put(double.class,       "DOUBLE");
            put(String.class,       "NVARCHAR");
            put(BigDecimal.class,   "DECIMAL");
            put(BigInteger.class,   "BIGINT");
        }
    };

    private static final Map<Class<?>, TypeDecorator> TYPE_DECORATORS = new HashMap<Class<?>, TypeDecorator>() {
        {
            TypeDecorator notNullDecorator = (type, valueObject) -> {
                Optional<NotNull> metadata = valueObject.getMetadata(NotNull.class);

                if(!metadata.isPresent())
                    return type;
                else
                    return type + " NOT NULL";
            };

            TypeDecorator placer = (type, valueObject) -> type + " %s";

            TypeDecorator integerPrecisionDecorator = of((type, valueObject) -> {
                Optional<Precision> metadata = valueObject.getMetadata(Precision.class);

                if(!metadata.isPresent())
                    return type;
                else
                    return type + "(" + metadata.get().integer() + ")";
            }, placer, notNullDecorator);

            TypeDecorator decimalPrecisionDecorator = of((type, valueObject) -> {
                Optional<Precision> metadata = valueObject.getMetadata(Precision.class);

                if(!metadata.isPresent())
                    return type;

                Precision precision = metadata.get();

                return type + "(" + precision.integer() + "," + precision.decimal() + ")";
            }, placer, notNullDecorator);

            put(String.class, of((type, valueObject) -> {
                Optional<Size> metadata = valueObject.getMetadata(Size.class);

                if(!metadata.isPresent())
                    return type;
                else
                    return type + "(" + metadata.get().value() + ")";
            }, placer, notNullDecorator));

            put(short.class, integerPrecisionDecorator);
            put(int.class, integerPrecisionDecorator);
            put(long.class, integerPrecisionDecorator);
            put(BigInteger.class, integerPrecisionDecorator);

            put(float.class, decimalPrecisionDecorator);
            put(double.class, decimalPrecisionDecorator);
            put(BigDecimal.class, decimalPrecisionDecorator);
        }
    };

    private interface TypeDecorator
    {
        String decorate(String type, ValueObject valueObject);
    }

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
}