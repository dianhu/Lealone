/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.router;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;

import org.lealone.cluster.config.DatabaseDescriptor;
import org.lealone.cluster.dht.Token;
import org.lealone.cluster.gms.FailureDetector;
import org.lealone.cluster.gms.Gossiper;
import org.lealone.cluster.service.StorageService;
import org.lealone.cluster.utils.Utils;
import org.lealone.common.message.DbException;
import org.lealone.common.util.New;
import org.lealone.db.SessionPool;
import org.lealone.db.Command;
import org.lealone.db.ServerSession;
import org.lealone.db.Session;
import org.lealone.db.result.Result;
import org.lealone.db.result.Row;
import org.lealone.db.schema.Schema;
import org.lealone.db.table.TableFilter;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueUuid;
import org.lealone.sql.StatementBase;
import org.lealone.sql.ddl.DefineStatement;
import org.lealone.sql.dml.Delete;
import org.lealone.sql.dml.Insert;
import org.lealone.sql.dml.InsertOrMerge;
import org.lealone.sql.dml.Merge;
import org.lealone.sql.dml.Query;
import org.lealone.sql.dml.Select;
import org.lealone.sql.dml.Update;
import org.lealone.sql.expression.Parameter;
import org.lealone.sql.router.CommandParallel;
import org.lealone.sql.router.MergedResult;
import org.lealone.sql.router.Router;
import org.lealone.sql.router.SerializedResult;
import org.lealone.sql.router.SortedResult;

import com.google.common.collect.Iterables;

public class P2PRouter implements Router {
    private static final Random random = new Random(System.currentTimeMillis());
    private static final P2PRouter INSTANCE = new P2PRouter();

    public static P2PRouter getInstance() {
        return INSTANCE;
    }

    protected P2PRouter() {
    }

    @Override
    public int executeDefineCommand(DefineStatement defineCommand) {
        if (defineCommand.isLocal())
            return defineCommand.updateLocal();

        InetAddress seedEndpoint = Gossiper.instance.getFirstLiveSeedEndpoint();
        if (seedEndpoint == null)
            throw new RuntimeException("no live seed endpoint");

        if (!seedEndpoint.equals(Utils.getBroadcastAddress())) {
            ServerSession session = defineCommand.getSession();
            Session fs = null;
            try {
                fs = SessionPool.getSeedEndpointSession(session, session.getURL(seedEndpoint));
                Command fc = SessionPool.getCommand(fs, defineCommand.getSQL(), defineCommand.getParameters(),
                        defineCommand.getFetchSize());
                return fc.executeUpdate();
            } catch (Exception e) {
                throw DbException.convert(e);
            } finally {
                if (fs != null)
                    fs.close();
            }
        }

        // 从seed节点上转发命令到其他节点时会携带一个"TOKEN"参数，
        // 如果在其他节点上又转发另一条命令过来，那么会构成一个循环，
        // 此时就不能用this当同步对象，会产生死锁。
        Object lock = this;
        Properties properties = defineCommand.getSession().getOriginalProperties();
        if (properties != null && properties.getProperty("TOKEN") != null)
            lock = properties;

        // 在seed节点上串行执行所有的defineCommand
        synchronized (lock) {
            properties.setProperty("TOKEN", "1");

            Set<InetAddress> liveMembers = Gossiper.instance.getLiveMembers();
            List<Callable<Integer>> commands = New.arrayList(liveMembers.size());

            liveMembers.remove(Utils.getBroadcastAddress());
            commands.add(defineCommand);
            try {
                for (InetAddress endpoint : liveMembers) {
                    commands.add(createUpdateCallable(endpoint, defineCommand));
                }
                return CommandParallel.executeUpdateCallable(commands);
            } catch (Exception e) {
                throw DbException.convert(e);
            } finally {
                properties.remove("TOKEN");
            }
        }
    }

    @Override
    public int executeInsert(Insert insert) {
        if (insert.isLocal())
            return insert.updateLocal();

        if (insert.getQuery() != null)
            return executeInsertOrMergeFromQuery(insert.getQuery(), insert, insert);

        return executeInsertOrMerge(insert);
    }

    @Override
    public int executeMerge(Merge merge) {
        if (merge.isLocal())
            return merge.updateLocal();

        if (merge.getQuery() != null)
            return executeInsertOrMergeFromQuery(merge.getQuery(), merge, merge);

        return executeInsertOrMerge(merge);
    }

    private static int executeInsertOrMergeFromQuery(Query query, StatementBase p, Callable<Integer> callable) {
        List<InetAddress> targetEndpoints = getTargetEndpointsIfEqual(query.getTopFilters().get(0));
        if (targetEndpoints != null) {
            boolean isLocal = targetEndpoints.contains(Utils.getBroadcastAddress());
            if (isLocal) {
                p.setLocal(true);
                try {
                    return callable.call();
                } catch (Exception e) {
                    throw DbException.convert(e);
                }
            }

            int size = targetEndpoints.size();
            InetAddress endpoint;
            if (size == 1)
                endpoint = targetEndpoints.get(0);
            else
                endpoint = targetEndpoints.get(random.nextInt(size));

            try {
                return createCommand(endpoint, p).executeUpdate();
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        } else {
            Set<InetAddress> liveMembers = Gossiper.instance.getLiveMembers();
            List<Callable<Integer>> commands = New.arrayList(liveMembers.size());
            liveMembers.remove(Utils.getBroadcastAddress());
            commands.add(callable);
            String sql = p.getSQL();
            p.setLocal(true);
            try {
                for (InetAddress endpoint : liveMembers) {
                    commands.add(createUpdateCallable(endpoint, p, sql));
                }
                return CommandParallel.executeUpdateCallable(commands);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
    }

    private static int executeInsertOrMerge(InsertOrMerge iom) {
        final String localDataCenter = DatabaseDescriptor.getEndpointSnitch()
                .getDatacenter(Utils.getBroadcastAddress());
        Schema schema = iom.getTable().getSchema();
        List<Row> localRows = null;
        Map<InetAddress, List<Row>> localDataCenterRows = null;
        Map<InetAddress, List<Row>> remoteDataCenterRows = null;

        Value partitionKey;
        for (Row row : iom.getRows()) {
            partitionKey = row.getRowKey();
            // 不存在PRIMARY KEY时，随机生成一个
            if (partitionKey == null)
                partitionKey = ValueUuid.getNewRandom();
            Token tk = StorageService.getPartitioner().getToken(ByteBuffer.wrap(partitionKey.getBytesNoCopy()));
            List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(schema, tk);
            Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetaData().pendingEndpointsFor(
                    tk, schema.getFullName());

            Iterable<InetAddress> targets = Iterables.concat(naturalEndpoints, pendingEndpoints);
            for (InetAddress destination : targets) {
                if (FailureDetector.instance.isAlive(destination)) {
                    if (destination.equals(Utils.getBroadcastAddress())) {
                        if (localRows == null)
                            localRows = New.arrayList();
                        localRows.add(row);
                    } else {
                        String dc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(destination);
                        if (localDataCenter.equals(dc)) {
                            if (localDataCenterRows == null)
                                localDataCenterRows = New.hashMap();

                            List<Row> rows = localDataCenterRows.get(destination);
                            if (rows == null) {
                                rows = New.arrayList();
                                localDataCenterRows.put(destination, rows);
                            }
                            rows.add(row);
                        } else {
                            if (remoteDataCenterRows == null)
                                remoteDataCenterRows = New.hashMap();

                            List<Row> rows = remoteDataCenterRows.get(destination);
                            if (rows == null) {
                                rows = New.arrayList();
                                remoteDataCenterRows.put(destination, rows);
                            }
                            rows.add(row);
                        }
                    }
                }
            }
        }

        List<Callable<Integer>> commands = New.arrayList();
        int updateCount = 0;
        try {
            createInsertOrMergeCallable(iom, commands, localDataCenterRows);
            createInsertOrMergeCallable(iom, commands, remoteDataCenterRows);

            if (localRows != null) {
                iom.setRows(localRows);
                commands.add(iom);
            }

            updateCount = CommandParallel.executeUpdateCallable(commands);
        } catch (Exception e) {
            throw DbException.convert(e);
        }

        return updateCount;
    }

    private static void createInsertOrMergeCallable(InsertOrMerge iom, //
            List<Callable<Integer>> commands, Map<InetAddress, List<Row>> rows) throws Exception {
        if (rows != null) {
            for (Map.Entry<InetAddress, List<Row>> e : rows.entrySet()) {
                commands.add(createUpdateCallable(e.getKey(), (StatementBase) iom, iom.getPlanSQL(e.getValue())));
            }
        }
    }

    @Override
    public int executeDelete(Delete delete) {
        if (delete.isLocal())
            return delete.updateLocal();

        return executeUpdateOrDelete(delete.getTableFilter(), delete);
    }

    @Override
    public int executeUpdate(Update update) {
        if (update.isLocal())
            return update.updateLocal();

        return executeUpdateOrDelete(update.getTableFilter(), update);
    }

    @SuppressWarnings("unchecked")
    private int executeUpdateOrDelete(TableFilter tableFilter, StatementBase p) {
        List<InetAddress> targetEndpoints = getTargetEndpointsIfEqual(tableFilter);
        if (targetEndpoints != null) {
            List<Callable<Integer>> commands = New.arrayList(targetEndpoints.size());

            try {
                for (InetAddress endpoint : targetEndpoints) {
                    if (endpoint.equals(Utils.getBroadcastAddress())) {
                        commands.add((Callable<Integer>) p);
                    } else {
                        commands.add(createUpdateCallable(endpoint, p));
                    }
                }
                return CommandParallel.executeUpdateCallable(commands);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        } else {
            Set<InetAddress> liveMembers = Gossiper.instance.getLiveMembers();
            List<Callable<Integer>> commands = New.arrayList(liveMembers.size());
            try {
                for (InetAddress endpoint : liveMembers) {
                    if (endpoint.equals(Utils.getBroadcastAddress())) {
                        commands.add((Callable<Integer>) p);
                    } else {
                        commands.add(createUpdateCallable(endpoint, p, p.getSQL()));
                    }
                }
                return CommandParallel.executeUpdateCallable(commands);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
    }

    @Override
    public Result executeSelect(Select select, int maxRows, boolean scrollable) {
        if (select.isLocal())
            return select.queryLocal(maxRows);

        List<InetAddress> targetEndpoints = getTargetEndpointsIfEqual(select.getTopTableFilter());
        if (targetEndpoints != null) {
            boolean isLocal = targetEndpoints.contains(Utils.getBroadcastAddress());
            if (isLocal)
                return select.call();

            int size = targetEndpoints.size();
            InetAddress endpoint;
            if (size == 1)
                endpoint = targetEndpoints.get(0);
            else
                endpoint = targetEndpoints.get(random.nextInt(size));

            try {
                return createCommand(endpoint, select).executeQuery(maxRows, scrollable);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        } else {
            // TODO 处理有多副本的情况
            Set<InetAddress> liveMembers = Gossiper.instance.getLiveMembers();
            String sql = getSelectPlanSQL(select);

            try {
                if (!select.isGroupQuery() && select.getSortOrder() == null) {
                    List<Command> commands = New.arrayList(liveMembers.size());

                    // 在本地节点执行
                    liveMembers.remove(Utils.getBroadcastAddress());
                    commands.add(createNewLocalSelect(select, sql));

                    for (InetAddress endpoint : liveMembers) {
                        commands.add(createCommand(endpoint, select, sql));
                    }

                    return new SerializedResult(commands, maxRows, scrollable, select.getLimitRows());
                } else {
                    List<Callable<Result>> commands = New.arrayList(liveMembers.size());
                    for (InetAddress endpoint : liveMembers) {
                        if (endpoint.equals(Utils.getBroadcastAddress())) {
                            commands.add(createNewLocalSelect(select, sql));
                        } else {
                            commands.add(createSelectCallable(endpoint, select, sql, maxRows, scrollable));
                        }
                    }

                    List<Result> results = CommandParallel.executeSelectCallable(commands);

                    if (!select.isGroupQuery() && select.getSortOrder() != null)
                        return new SortedResult(maxRows, select.getSession(), select, results);

                    String newSQL = select.getPlanSQL(true, true);
                    Select newSelect = (Select) select.getSession().prepareStatement(newSQL, true);
                    newSelect.setLocal(true);

                    return new MergedResult(results, newSelect, select);
                }

            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
    }

    private static Select createNewLocalSelect(Select oldSelect, String sql) {
        if (!oldSelect.isGroupQuery() && oldSelect.getLimit() == null && oldSelect.getOffset() == null) {
            oldSelect.setLocal(true);
            return oldSelect;
        }

        StatementBase p = (StatementBase) oldSelect.getSession().prepareStatement(sql, true);
        p.setLocal(true);
        p.setFetchSize(oldSelect.getFetchSize());
        ArrayList<Parameter> oldParams = oldSelect.getParameters();
        ArrayList<Parameter> newParams = p.getParameters();
        for (int i = 0, size = newParams.size(); i < size; i++) {
            newParams.get(i).setValue(oldParams.get(i).getParamValue());
        }
        return (Select) p;
    }

    private static String getSelectPlanSQL(Select select) {
        if (select.isGroupQuery() || select.getLimit() != null || select.getOffset() != null)
            return select.getPlanSQL(true);
        else
            return select.getSQL();
    }

    private static Callable<Result> createSelectCallable(InetAddress endpoint, Select select, String sql,
            final int maxRows, final boolean scrollable) throws Exception {
        final Command c = createCommand(endpoint, select, sql);

        Callable<Result> call = new Callable<Result>() {
            @Override
            public Result call() throws Exception {
                return c.executeQuery(maxRows, scrollable);
            }
        };

        return call;
    }

    private static List<InetAddress> getTargetEndpointsIfEqual(TableFilter tableFilter) {
        Value pk = StatementBase.getPartitionKey(tableFilter);

        if (pk != null) {
            Schema schema = tableFilter.getTable().getSchema();
            Token tk = StorageService.getPartitioner().getToken(ByteBuffer.wrap(pk.getBytesNoCopy()));
            List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(schema, tk);
            Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetaData().pendingEndpointsFor(
                    tk, schema.getFullName());

            naturalEndpoints.addAll(pendingEndpoints);
            return naturalEndpoints;
        }

        return null;
    }

    private static Callable<Integer> createUpdateCallable(InetAddress endpoint, StatementBase p) throws Exception {
        return createUpdateCallable(endpoint, p, p.getSQL());
    }

    private static Callable<Integer> createUpdateCallable(InetAddress endpoint, StatementBase p, String sql)
            throws Exception {
        final Command c = createCommand(endpoint, p, sql);
        Callable<Integer> call = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return c.executeUpdate();
            }
        };

        return call;
    }

    private static Command createCommand(InetAddress endpoint, StatementBase p) throws Exception {
        return SessionPool.getCommand(p.getSession(), p, p.getSession().getURL(endpoint), p.getSQL());
    }

    private static Command createCommand(InetAddress endpoint, StatementBase p, String sql) throws Exception {
        return SessionPool.getCommand(p.getSession(), p, p.getSession().getURL(endpoint), sql);
    }
}
