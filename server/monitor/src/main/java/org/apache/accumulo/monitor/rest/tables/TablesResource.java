/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.monitor.rest.tables;

import static org.apache.accumulo.monitor.util.ParameterValidator.ALPHA_NUM_REGEX_TABLE_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.accumulo.core.clientImpl.Tables;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.accumulo.core.master.thrift.TableInfo;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.monitor.Monitor;
import org.apache.accumulo.monitor.rest.tservers.TabletServer;
import org.apache.accumulo.monitor.rest.tservers.TabletServers;
import org.apache.accumulo.server.master.state.MetaDataTableScanner;
import org.apache.accumulo.server.master.state.TabletLocationState;
import org.apache.accumulo.server.tables.TableManager;
import org.apache.accumulo.server.util.TableInfoUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.io.Text;

/**
 * Generates a tables list from the Monitor as a JSON object
 *
 * @since 2.0.0
 */
@Path("/tables")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class TablesResource {

  @Inject
  private Monitor monitor;

  private static final TabletServerStatus NO_STATUS = new TabletServerStatus();

  /**
   * Generates a list of all the tables
   *
   * @return list with all tables
   */
  @GET
  public TableInformationList getTables() {
    return getTables(monitor);
  }

  public static TableInformationList getTables(Monitor monitor) {
    TableInformationList tableList = new TableInformationList();
    SortedMap<TableId,TableInfo> tableStats = new TreeMap<>();

    if (monitor.getMmi() != null && monitor.getMmi().tableMap != null) {
      for (Map.Entry<String,TableInfo> te : monitor.getMmi().tableMap.entrySet()) {
        tableStats.put(TableId.of(te.getKey()), te.getValue());
      }
    }

    Map<String,Double> compactingByTable = TableInfoUtil.summarizeTableStats(monitor.getMmi());
    TableManager tableManager = monitor.getContext().getTableManager();

    // Add tables to the list
    for (Map.Entry<String,TableId> entry : Tables.getNameToIdMap(monitor.getContext()).entrySet()) {
      String tableName = entry.getKey();
      TableId tableId = entry.getValue();
      TableInfo tableInfo = tableStats.get(tableId);
      TableState tableState = tableManager.getTableState(tableId);

      if (tableInfo != null && !tableState.equals(TableState.OFFLINE)) {
        Double holdTime = compactingByTable.get(tableId.canonical());
        if (holdTime == null) {
          holdTime = 0.;
        }

        tableList.addTable(
            new TableInformation(tableName, tableId, tableInfo, holdTime, tableState.name()));
      } else {
        tableList.addTable(new TableInformation(tableName, tableId, tableState.name()));
      }
    }
    return tableList;
  }

  /**
   * Generates a list of participating tservers for a table
   *
   * @param tableIdStr
   *          Table ID to find participating tservers
   * @return List of participating tservers
   */
  @Path("{tableId}")
  @GET
  public TabletServers getParticipatingTabletServers(@PathParam("tableId") @NotNull @Pattern(
      regexp = ALPHA_NUM_REGEX_TABLE_ID) String tableIdStr) {
    String rootTabletLocation = monitor.getContext().getRootTabletLocation();
    TableId tableId = TableId.of(tableIdStr);

    TabletServers tabletServers = new TabletServers(monitor.getMmi().tServerInfo.size());

    if (StringUtils.isBlank(tableIdStr)) {
      return tabletServers;
    }

    TreeSet<String> locs = new TreeSet<>();
    if (RootTable.ID.equals(tableId)) {
      locs.add(rootTabletLocation);
    } else {
      String systemTableName =
          MetadataTable.ID.equals(tableId) ? RootTable.NAME : MetadataTable.NAME;
      MetaDataTableScanner scanner = new MetaDataTableScanner(monitor.getContext(),
          new Range(TabletsSection.getRow(tableId, new Text()),
              TabletsSection.getRow(tableId, null)),
          systemTableName);

      while (scanner.hasNext()) {
        TabletLocationState state = scanner.next();
        if (state.current != null) {
          try {
            locs.add(state.current.hostPort());
          } catch (Exception ex) {
            scanner.close();
            return tabletServers;
          }
        }
      }
      scanner.close();
    }

    List<TabletServerStatus> tservers = new ArrayList<>();
    if (monitor.getMmi() != null) {
      for (TabletServerStatus tss : monitor.getMmi().tServerInfo) {
        try {
          if (tss.name != null && locs.contains(tss.name)) {
            tservers.add(tss);
          }
        } catch (Exception ex) {
          return tabletServers;
        }
      }
    }

    // Adds tservers to the list
    for (TabletServerStatus status : tservers) {
      if (status == null) {
        status = NO_STATUS;
      }
      TableInfo summary = TableInfoUtil.summarizeTableStats(status);
      if (tableId != null) {
        summary = status.tableMap.get(tableId.canonical());
      }
      if (summary == null) {
        continue;
      }

      TabletServer tabletServerInfo = new TabletServer();
      tabletServerInfo.server.updateTabletServerInfo(monitor, status, summary);

      tabletServers.addTablet(tabletServerInfo);
    }

    return tabletServers;

  }

}
