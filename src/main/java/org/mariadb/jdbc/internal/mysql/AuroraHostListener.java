/*
MariaDB Client for Java

Copyright (c) 2012 Monty Program Ab.

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:

Copyright (c) 2009-2011, Marcus Eriksson

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

package org.mariadb.jdbc.internal.mysql;

import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.internal.common.QueryException;
import org.mariadb.jdbc.internal.common.query.MySQLQuery;
import org.mariadb.jdbc.internal.common.queryresults.QueryResult;
import org.mariadb.jdbc.internal.common.queryresults.ResultSetType;
import org.mariadb.jdbc.internal.common.queryresults.SelectQueryResult;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class AuroraHostListener extends MultiHostListener {
    private final static Logger log = Logger.getLogger(AuroraHostListener.class.getName());

    public AuroraHostListener() {
        masterProtocol = null;
        secondaryProtocol = null;
    }

    public void initializeConnection() throws QueryException, SQLException {
        this.masterProtocol = (MultiNodesProtocol)this.proxy.currentProtocol;
        this.secondaryProtocol = new MultiNodesProtocol(this.masterProtocol.jdbcUrl,
                this.masterProtocol.getUsername(),
                this.masterProtocol.getPassword(),
                this.masterProtocol.getInfo());
        this.secondaryProtocol.setProxy(proxy);

        //set failover data to force connection
        proxy.masterHostFailTimestamp = System.currentTimeMillis();
        proxy.secondaryHostFailTimestamp = System.currentTimeMillis();

        //TODO for perf : initial masterPrococol load and replace by connected one -> can be better

        launchSearchLoopConnection();
    }

    /**
     * search a valid connection for failed one.
     * A Node can be a master or a replica depending on the cluster state.
     * so search for each host until found all the failed connection.
     * By default, search for the host not down, and recheck the down one after if not found valid connections.
     * @throws QueryException
     * @throws SQLException
     */
    public void launchSearchLoopConnection() throws QueryException, SQLException {
        proxy.currentConnectionAttempts++;
        proxy.lastRetry = System.currentTimeMillis();

        if (proxy.currentConnectionAttempts >= proxy.retriesAllDown) {
            throw new QueryException("Too many reconnection attempts ("+proxy.retriesAllDown+")");
        }

        AuroraMultiNodesProtocol newProtocol = new AuroraMultiNodesProtocol(this.masterProtocol.jdbcUrl,
                this.masterProtocol.getUsername(),
                this.masterProtocol.getPassword(),
                this.masterProtocol.getInfo());
        newProtocol.setProxy(proxy);
        List<HostAddress> loopAddress = Arrays.asList(this.masterProtocol.jdbcUrl.getHostAddresses().clone());
        List<HostAddress> failAddress = new ArrayList<HostAddress>();

        boolean searchForMaster = false;
        boolean searchForSecondary = false;

        if (proxy.masterHostFailTimestamp == 0) {
            loopAddress.remove(masterProtocol.currentHost);
            failAddress.add(masterProtocol.currentHost);
        } else searchForMaster = true;

        if (proxy.secondaryHostFailTimestamp == 0) {
            loopAddress.remove(secondaryProtocol.currentHost);
            failAddress.add(secondaryProtocol.currentHost);
            searchForLikelyMasterFromSlave(loopAddress);
        } else searchForSecondary = true;

        if ((searchForMaster || searchForSecondary) && isLooping.compareAndSet(false, true)) {
            newProtocol.loop(this, loopAddress, failAddress, searchForMaster, searchForSecondary);
        }
    }

    public synchronized HandleErrorResult secondaryFail(Method method, Object[] args) throws Throwable {

        if (proxy.masterHostFailTimestamp == 0) {
            //in multiHost, switch temporary to Master
            log.finest("switching to master connection");
            syncConnection(this.secondaryProtocol, this.masterProtocol);
            proxy.currentProtocol = this.masterProtocol;

            //since replica are restarted after a change of master, checking if the master as stayed master
            if (!masterProtocol.checkIfMaster()) {
                this.secondaryProtocol = this.masterProtocol;
                proxy.masterHostFailTimestamp = System.currentTimeMillis();
            }

            if (isLooping.compareAndSet(false, true)) {
                exec.scheduleAtFixedRate(new FailLoop(this), 0, 250, TimeUnit.MILLISECONDS);
            }

            //now that we are on an active connection, relaunched result if the result was not crashing the master
            return relaunchOperation(method, args);
        }
        //launch reconnection loop
        if (isLooping.compareAndSet(false, true)) {
            exec.scheduleAtFixedRate(new FailLoop(this), 0, 250, TimeUnit.MILLISECONDS);
        }
        return new HandleErrorResult();
    }

    /**
     * Aurora replica does'nt have the master endpoint but the master instance name.
     * since the end point normaly use the instance name like "instancename.some_ugly_string.region.rds.amazonaws.com", if an endpoint start with this instance name, it will be checked first.
     * @param loopAddress
     */
    private void searchForLikelyMasterFromSlave(List<HostAddress> loopAddress) {
        try {
            QueryResult queryResult = secondaryProtocol.executeQuery(new MySQLQuery("select server_id from information_schema.replica_host_status where session_id = 'MASTER_SESSION_ID'"));
            if (queryResult.getResultSetType() == ResultSetType.SELECT && ((SelectQueryResult) queryResult).next()) {
                String masterHostName = ((SelectQueryResult) queryResult).getValueObject(0).getString();
                for (int i=0;i<loopAddress.size();i++) {
                    if (loopAddress.get(i).host.startsWith(masterHostName)) {
                        if (i==0) return;
                        else {
                            HostAddress probableMaster = loopAddress.get(i);
                            loopAddress.remove(i);
                            loopAddress.add(0, probableMaster);
                            return;
                        }
                    }
                }
            }
        } catch(Exception ioe) {
            //do nothing
        }


    }

}
