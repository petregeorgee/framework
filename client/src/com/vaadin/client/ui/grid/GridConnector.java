/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.client.ui.grid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONValue;
import com.vaadin.client.communication.StateChangeEvent;
import com.vaadin.client.ui.AbstractComponentConnector;
import com.vaadin.client.ui.grid.renderers.AbstractRendererConnector;
import com.vaadin.shared.ui.Connect;
import com.vaadin.shared.ui.grid.ColumnGroupRowState;
import com.vaadin.shared.ui.grid.ColumnGroupState;
import com.vaadin.shared.ui.grid.GridClientRpc;
import com.vaadin.shared.ui.grid.GridColumnState;
import com.vaadin.shared.ui.grid.GridState;
import com.vaadin.shared.ui.grid.ScrollDestination;

/**
 * Connects the client side {@link Grid} widget with the server side
 * {@link com.vaadin.ui.components.grid.Grid} component.
 * <p>
 * The Grid is typed to JSONObject. The structure of the JSONObject is described
 * at {@link com.vaadin.shared.data.DataProviderRpc#setRowData(int, List)
 * DataProviderRpc.setRowData(int, List)}.
 * 
 * @since 7.4
 * @author Vaadin Ltd
 */
@Connect(com.vaadin.ui.components.grid.Grid.class)
public class GridConnector extends AbstractComponentConnector {

    /**
     * Custom implementation of the custom grid column using a String[] to
     * represent the cell value and String as a column type.
     */
    private class CustomGridColumn extends GridColumn<Object, JSONObject> {

        private final String id;

        private AbstractRendererConnector<Object> rendererConnector;

        public CustomGridColumn(String id,
                AbstractRendererConnector<Object> rendererConnector) {
            super(rendererConnector.getRenderer());
            this.rendererConnector = rendererConnector;
            this.id = id;
        }

        @Override
        public Object getValue(final JSONObject obj) {
            final JSONValue rowData = obj.get(GridState.JSONKEY_DATA);
            final JSONArray rowDataArray = rowData.isArray();
            assert rowDataArray != null : "Was unable to parse JSON into an array: "
                    + rowData;

            final int columnIndex = resolveCurrentIndexFromState();
            final JSONValue columnValue = rowDataArray.get(columnIndex);
            return rendererConnector.decode(columnValue);
        }

        /*
         * Only used to check that the renderer connector will not change during
         * the column lifetime.
         * 
         * TODO remove once support for changing renderers is implemented
         */
        private AbstractRendererConnector<Object> getRendererConnector() {
            return rendererConnector;
        }

        private int resolveCurrentIndexFromState() {
            List<GridColumnState> columns = getState().columns;
            int numColumns = columns.size();
            for (int index = 0; index < numColumns; index++) {
                if (columns.get(index).id.equals(id)) {
                    return index;
                }
            }
            return -1;
        }
    }

    /**
     * Maps a generated column id to a grid column instance
     */
    private Map<String, CustomGridColumn> columnIdToColumn = new HashMap<String, CustomGridColumn>();

    @Override
    @SuppressWarnings("unchecked")
    public Grid<JSONObject> getWidget() {
        return (Grid<JSONObject>) super.getWidget();
    }

    @Override
    public GridState getState() {
        return (GridState) super.getState();
    }

    @Override
    protected void init() {
        super.init();

        registerRpc(GridClientRpc.class, new GridClientRpc() {
            @Override
            public void scrollToStart() {
                getWidget().scrollToStart();
            }

            @Override
            public void scrollToEnd() {
                getWidget().scrollToEnd();
            }

            @Override
            public void scrollToRow(int row, ScrollDestination destination) {
                getWidget().scrollToRow(row, destination);
            }
        });
    }

    @Override
    public void onStateChanged(StateChangeEvent stateChangeEvent) {
        super.onStateChanged(stateChangeEvent);

        // Column updates
        if (stateChangeEvent.hasPropertyChanged("columns")) {

            int totalColumns = getState().columns.size();

            // Remove old columns
            purgeRemovedColumns();

            int currentColumns = getWidget().getColumnCount();

            // Add new columns
            for (int columnIndex = currentColumns; columnIndex < totalColumns; columnIndex++) {
                addColumnFromStateChangeEvent(columnIndex);
            }

            // Update old columns
            for (int columnIndex = 0; columnIndex < currentColumns; columnIndex++) {
                // FIXME Currently updating all column header / footers when a
                // change in made in one column. When the framework supports
                // quering a specific item in a list then it should do so here.
                updateColumnFromStateChangeEvent(columnIndex);
            }
        }

        // Header
        if (stateChangeEvent.hasPropertyChanged("columnHeadersVisible")) {
            getWidget()
                    .setColumnHeadersVisible(getState().columnHeadersVisible);
        }

        // Footer
        if (stateChangeEvent.hasPropertyChanged("columnFootersVisible")) {
            getWidget()
                    .setColumnFootersVisible(getState().columnFootersVisible);
        }

        // Column row groups
        if (stateChangeEvent.hasPropertyChanged("columnGroupRows")) {
            updateColumnGroupsFromStateChangeEvent();
        }

        if (stateChangeEvent.hasPropertyChanged("lastFrozenColumnId")) {
            String frozenColId = getState().lastFrozenColumnId;
            if (frozenColId != null) {
                CustomGridColumn column = columnIdToColumn.get(frozenColId);
                assert column != null : "Column to be frozen could not be found (id:"
                        + frozenColId + ")";
                getWidget().setLastFrozenColumn(column);
            } else {
                getWidget().setLastFrozenColumn(null);
            }
        }

        /*
         * @DelegateToWidget annotation doesn't work because of
         * http://dev.vaadin.com/ticket/12900. Remove manual code and uncomment
         * annotations at GridState once fixed.
         */

        if (stateChangeEvent.hasPropertyChanged("heightByRows")) {
            getWidget().setHeightByRows(getState().heightByRows);
        }

        if (stateChangeEvent.hasPropertyChanged("heightMode")) {
            getWidget().setHeightMode(getState().heightMode);
        }
    }

    /**
     * Updates a column from a state change event.
     * 
     * @param columnIndex
     *            The index of the column to update
     */
    private void updateColumnFromStateChangeEvent(int columnIndex) {
        GridColumn<?, JSONObject> column = getWidget().getColumn(columnIndex);
        GridColumnState columnState = getState().columns.get(columnIndex);
        updateColumnFromState(column, columnState);

        if (columnState.rendererConnector != ((CustomGridColumn) column)
                .getRendererConnector()) {
            throw new UnsupportedOperationException(
                    "Changing column renderer after initialization is currently unsupported");
        }
    }

    /**
     * Adds a new column to the grid widget from a state change event
     * 
     * @param columnIndex
     *            The index of the column, according to how it
     */
    private void addColumnFromStateChangeEvent(int columnIndex) {
        GridColumnState state = getState().columns.get(columnIndex);
        @SuppressWarnings("unchecked")
        CustomGridColumn column = new CustomGridColumn(state.id,
                ((AbstractRendererConnector<Object>) state.rendererConnector));
        columnIdToColumn.put(state.id, column);

        // Adds a column to grid, and registers Grid with the column.
        getWidget().addColumn(column, columnIndex);

        /*
         * Have to update state _after_ the column has been added to the grid as
         * then, and only then, the column will call the grid which in turn will
         * call the escalator's refreshRow methods on header/footer/body and
         * visually refresh the row. If this is done in the reverse order the
         * first column state update will be lost as no grid instance is
         * present.
         */
        updateColumnFromState(column, state);
    }

    /**
     * Updates the column values from a state
     * 
     * @param column
     *            The column to update
     * @param state
     *            The state to get the data from
     */
    private static void updateColumnFromState(GridColumn<?, JSONObject> column,
            GridColumnState state) {
        column.setVisible(state.visible);
        column.setHeaderCaption(state.header);
        column.setFooterCaption(state.footer);
        column.setWidth(state.width);
        column.setSortable(state.sortable);
    }

    /**
     * Removes any orphan columns that has been removed from the state from the
     * grid
     */
    private void purgeRemovedColumns() {

        // Get columns still registered in the state
        Set<String> columnsInState = new HashSet<String>();
        for (GridColumnState columnState : getState().columns) {
            columnsInState.add(columnState.id);
        }

        // Remove column no longer in state
        Iterator<String> columnIdIterator = columnIdToColumn.keySet()
                .iterator();
        while (columnIdIterator.hasNext()) {
            String id = columnIdIterator.next();
            if (!columnsInState.contains(id)) {
                CustomGridColumn column = columnIdToColumn.get(id);
                columnIdIterator.remove();
                getWidget().removeColumn(column);
            }
        }
    }

    /**
     * Updates the column groups from a state change
     */
    private void updateColumnGroupsFromStateChangeEvent() {

        // FIXME When something changes the header/footer rows will be
        // re-created. At some point we should optimize this so partial updates
        // can be made on the header/footer.
        for (ColumnGroupRow<JSONObject> row : getWidget().getColumnGroupRows()) {
            getWidget().removeColumnGroupRow(row);
        }

        for (ColumnGroupRowState rowState : getState().columnGroupRows) {
            ColumnGroupRow<JSONObject> row = getWidget().addColumnGroupRow();
            row.setFooterVisible(rowState.footerVisible);
            row.setHeaderVisible(rowState.headerVisible);

            for (ColumnGroupState groupState : rowState.groups) {
                List<GridColumn<Object, JSONObject>> columns = new ArrayList<GridColumn<Object, JSONObject>>();
                for (String columnId : groupState.columns) {
                    CustomGridColumn column = columnIdToColumn.get(columnId);
                    columns.add(column);
                }
                @SuppressWarnings("unchecked")
                final GridColumn<?, JSONObject>[] gridColumns = columns
                        .toArray(new GridColumn[columns.size()]);
                ColumnGroup<JSONObject> group = row.addGroup(gridColumns);
                group.setFooterCaption(groupState.footer);
                group.setHeaderCaption(groupState.header);
            }
        }
    }
}
