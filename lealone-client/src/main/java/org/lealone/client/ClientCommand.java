/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client;

import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.client.result.ClientResult;
import org.lealone.client.result.RowCountDeterminedClientResult;
import org.lealone.client.result.RowCountUndeterminedClientResult;
import org.lealone.common.message.DbException;
import org.lealone.common.message.Trace;
import org.lealone.common.util.New;
import org.lealone.db.Command;
import org.lealone.db.CommandParameter;
import org.lealone.db.Session;
import org.lealone.db.SysProperties;
import org.lealone.db.result.Result;
import org.lealone.db.value.Transfer;
import org.lealone.db.value.Value;

/**
 * Represents the client-side part of a SQL statement.
 * This class is not used in embedded mode.
 */
public class ClientCommand implements Command {

    private final Transfer transfer;
    private final ArrayList<CommandParameter> parameters;
    private final Trace trace;
    private final String sql;
    private final int fetchSize;
    private ClientSession session;
    private int id;
    private boolean isQuery;
    // private boolean readonly;
    private final int created;

    public ClientCommand(ClientSession session, Transfer transfer, String sql, int fetchSize) {
        this.transfer = transfer;
        trace = session.getTrace();
        this.sql = sql;
        parameters = New.arrayList();
        prepare(session, true);
        // set session late because prepare might fail - in this case we don't
        // need to close the object
        this.session = session;
        this.fetchSize = fetchSize;
        created = session.getLastReconnect();
    }

    private void prepare(ClientSession s, boolean createParams) {
        id = s.getNextId();
        try {
            if (createParams) {
                s.traceOperation("SESSION_PREPARE_READ_PARAMS", id);
                transfer.writeInt(ClientSession.SESSION_PREPARE_READ_PARAMS).writeInt(id).writeString(sql);
            } else {
                s.traceOperation("SESSION_PREPARE", id);
                transfer.writeInt(ClientSession.SESSION_PREPARE).writeInt(id).writeString(sql);
            }
            s.done(transfer);
            isQuery = transfer.readBoolean();
            // readonly = transfer.readBoolean();
            transfer.readBoolean();
            int paramCount = transfer.readInt();
            if (createParams) {
                parameters.clear();
                for (int j = 0; j < paramCount; j++) {
                    ClientCommandParameter p = new ClientCommandParameter(j);
                    p.readMetaData(transfer);
                    parameters.add(p);
                }
            }
        } catch (IOException e) {
            s.handleException(e);
        }
    }

    @Override
    public boolean isQuery() {
        return isQuery;
    }

    @Override
    public ArrayList<CommandParameter> getParameters() {
        return parameters;
    }

    private void prepareIfRequired() {
        if (session.getLastReconnect() != created) {
            // in this case we need to prepare again in every case
            id = Integer.MIN_VALUE;
        }
        session.checkClosed();
        if (id <= session.getCurrentId() - SysProperties.SERVER_CACHED_OBJECTS) {
            // object is too old - we need to prepare again
            prepare(session, false);
        }
    }

    @Override
    public Result getMetaData() {
        synchronized (session) {
            if (!isQuery) {
                return null;
            }
            int objectId = session.getNextId();
            ClientResult result = null;
            prepareIfRequired();
            try {
                session.traceOperation("COMMAND_GET_META_DATA", id);
                transfer.writeInt(ClientSession.COMMAND_GET_META_DATA).writeInt(id).writeInt(objectId);
                session.done(transfer);
                int columnCount = transfer.readInt();
                int rowCount = transfer.readInt();
                result = new RowCountDeterminedClientResult(session, transfer, objectId, columnCount, rowCount,
                        Integer.MAX_VALUE);
            } catch (IOException e) {
                session.handleException(e);
            }
            return result;
        }
    }

    @Override
    public Result executeQuery(int maxRows, boolean scrollable) {
        checkParameters();
        synchronized (session) {
            int objectId = session.getNextId();
            ClientResult result = null;
            prepareIfRequired();
            try {
                boolean isDistributedQuery = session.getTransaction() != null
                        && !session.getTransaction().isAutoCommit();
                if (isDistributedQuery) {
                    session.traceOperation("COMMAND_EXECUTE_DISTRIBUTED_QUERY", id);
                    transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_QUERY).writeInt(id).writeInt(objectId)
                            .writeInt(maxRows);
                } else {
                    session.traceOperation("COMMAND_EXECUTE_QUERY", id);
                    transfer.writeInt(ClientSession.COMMAND_EXECUTE_QUERY) //
                            .writeInt(id).writeInt(objectId).writeInt(maxRows);
                }
                int fetch;
                if (scrollable) {
                    fetch = Integer.MAX_VALUE;
                } else {
                    fetch = fetchSize;
                }
                transfer.writeInt(fetch);
                sendParameters(transfer);
                session.done(transfer);

                if (isDistributedQuery)
                    session.getTransaction().addLocalTransactionNames(transfer.readString());

                int columnCount = transfer.readInt();
                int rowCount = transfer.readInt();

                if (rowCount < 0)
                    result = new RowCountUndeterminedClientResult(session, transfer, objectId, columnCount, fetch);
                else
                    result = new RowCountDeterminedClientResult(session, transfer, objectId, columnCount, rowCount,
                            fetch);

            } catch (IOException e) {
                session.handleException(e);
            } catch (Exception e) {
                e.printStackTrace();
            }
            session.readSessionState();
            return result;
        }
    }

    @Override
    public int executeUpdate() {
        checkParameters();
        synchronized (session) {
            int updateCount = 0;
            // boolean autoCommit = false;
            prepareIfRequired();
            try {
                boolean isDistributedUpdate = session.getTransaction() != null
                        && !session.getTransaction().isAutoCommit();
                if (isDistributedUpdate) {
                    session.traceOperation("COMMAND_EXECUTE_DISTRIBUTED_UPDATE", id);
                    transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_UPDATE).writeInt(id);
                } else {
                    session.traceOperation("COMMAND_EXECUTE_UPDATE", id);
                    transfer.writeInt(ClientSession.COMMAND_EXECUTE_UPDATE).writeInt(id);
                }
                sendParameters(transfer);
                session.done(transfer);

                if (isDistributedUpdate)
                    session.getTransaction().addLocalTransactionNames(transfer.readString());

                updateCount = transfer.readInt();
                transfer.readBoolean();
                // autoCommit = transfer.readBoolean();
            } catch (IOException e) {
                session.handleException(e);
            } catch (Exception e) {
                // e.printStackTrace();
                throw e;
            }
            session.readSessionState();
            return updateCount;
        }
    }

    private void checkParameters() {
        for (CommandParameter p : parameters) {
            p.checkSet();
        }
    }

    private void sendParameters(Transfer transfer) throws IOException {
        int len = parameters.size();
        transfer.writeInt(len);
        for (CommandParameter p : parameters) {
            transfer.writeValue(p.getParamValue());
        }
    }

    @Override
    public void close() {
        if (session == null || session.isClosed()) {
            return;
        }
        synchronized (session) {
            session.traceOperation("COMMAND_CLOSE", id);
            try {
                transfer.writeInt(ClientSession.COMMAND_CLOSE).writeInt(id);
            } catch (IOException e) {
                trace.error(e, "close");
            }
        }
        session = null;
        try {
            for (CommandParameter p : parameters) {
                Value v = p.getParamValue();
                if (v != null) {
                    v.close();
                }
            }
        } catch (DbException e) {
            trace.error(e, "close");
        }
        parameters.clear();
    }

    /**
     * Cancel this current statement.
     */
    @Override
    public void cancel() {
        session.cancelStatement(id);
    }

    @Override
    public String toString() {
        return sql + Trace.formatParams(getParameters());
    }

    @Override
    public int getType() {
        return COMMAND;
    }

    int getId() {
        return id;
    }

    /**
     * A client side parameter.
     */
    private static class ClientCommandParameter implements CommandParameter {

        private Value value;
        private final int index;
        private int dataType = Value.UNKNOWN;
        private long precision;
        private int scale;
        private int nullable = ResultSetMetaData.columnNullableUnknown;

        public ClientCommandParameter(int index) {
            this.index = index;
        }

        @Override
        public void setValue(Value newValue, boolean closeOld) {
            if (closeOld && value != null) {
                value.close();
            }
            value = newValue;
        }

        @Override
        public Value getParamValue() {
            return value;
        }

        @Override
        public void checkSet() {
            if (value == null) {
                throw DbException.get(ErrorCode.PARAMETER_NOT_SET_1, "#" + (index + 1));
            }
        }

        @Override
        public boolean isValueSet() {
            return value != null;
        }

        @Override
        public int getType() {
            return value == null ? dataType : value.getType();
        }

        @Override
        public long getPrecision() {
            return value == null ? precision : value.getPrecision();
        }

        @Override
        public int getScale() {
            return value == null ? scale : value.getScale();
        }

        @Override
        public int getNullable() {
            return nullable;
        }

        /**
         * Write the parameter meta data from the transfer object.
         *
         * @param transfer the transfer object
         */
        public void readMetaData(Transfer transfer) throws IOException {
            dataType = transfer.readInt();
            precision = transfer.readLong();
            scale = transfer.readInt();
            nullable = transfer.readInt();
        }

        @Override
        public void setValue(Value value) {
            this.value = value;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public Value getParamValue(Session session) {
            return value;
        }

    }

}
