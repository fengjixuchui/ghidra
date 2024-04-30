/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.framework.main;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import docking.widgets.checkbox.GCheckBox;
import docking.widgets.label.GDLabel;
import docking.widgets.list.GListCellRenderer;
import generic.theme.GThemeDefaults.Colors;
import ghidra.framework.model.ToolConnection;
import ghidra.framework.model.ToolManager;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.Msg;

/**
 * Adds the listeners for the connection panel that shows 3 lists: one
 * for producers of event, one for consumers of events, and one
 * that shows events that are an intersection of the consumed and
 * produced events.
 */
class ToolConnectionPanel extends JPanel implements ListSelectionListener {

	private ToolConnectionDialog toolDialog;
	private ToolManager toolManager;

	private JList<PluginTool> consumerList; // list of receiver tools
	private JList<PluginTool> producerList; // list of source (of events)
	private JList<JCheckBox> eventList; // names of events generated by source
	private DefaultListModel<PluginTool> producerModel;
	private DefaultListModel<PluginTool> consumerModel;
	private GCheckBox[] checkboxes;
	private String[] eventNames;

	private final static String msgSource = "Tool Connection";

	ToolConnectionPanel(ToolConnectionDialog toolDialog, ToolManager toolManager) {
		super();
		this.toolDialog = toolDialog;
		this.toolManager = toolManager;
		initialize();
	}

	@Override
	public void valueChanged(ListSelectionEvent e) {
		toolDialog.setStatusText("");
		toolDialog.setConnectAllEnabled(false);
		toolDialog.setDisconnectAllEnabled(false);

		if (e.getValueIsAdjusting()) {
			return;
		}
		processSelection();
	}

	void setToolManager(ToolManager toolManager) {
		this.toolManager = toolManager;
		updateDisplay();
	}

	/**
	 * Update the list of tools. If any selections were made,
	 * restore them. This method is called because tools were either
	 * added or removed.
	 */
	void updateDisplay() {
		PluginTool producer = producerList.getSelectedValue();
		PluginTool consumer = consumerList.getSelectedValue();

		showData();

		// restore the selection
		if (producer != null && consumer != null) {
			int index = producerModel.indexOf(producer);

			if (index >= 0) {
				ListSelectionModel sm = producerList.getSelectionModel();
				// add to selection
				sm.addSelectionInterval(index, index);
			}

			index = consumerModel.indexOf(consumer);

			if (index >= 0) {
				ListSelectionModel sm = consumerList.getSelectionModel();
				// add to selection
				sm.addSelectionInterval(index, index);
			}
		}
		validate();
	}

	/**
	 * Populate the lists according to the type of panel.
	 */
	void showData() {

		// clear the event list
		eventList.setModel(new DefaultListModel<>());
		clearSelection();
		populateConsumerList();
		populateProducerList();
	}

	void clear() {
		this.consumerModel.clear();
		this.producerModel.clear();
	}

	/**
	 * Tool was added to the workspace; update the display.
	 * @param tool tool added
	 */
	void toolAdded(PluginTool tool) {
		String[] consumedEvents = tool.getConsumedToolEventNames();
		String[] producedEvents = tool.getToolEventNames();
		if (consumedEvents.length > 0) {
			consumerModel.addElement(tool);
		}
		if (producedEvents.length > 0) {
			producerModel.addElement(tool);
		}
		validate();
	}

	/**
	 * Tool was removed from a workspace; update the display.
	 * @param tool tool removed
	 */
	void toolRemoved(PluginTool tool) {
		int index = producerModel.indexOf(tool);
		if (index >= 0) {
			producerModel.remove(index);
		}
		index = consumerModel.indexOf(tool);
		if (index >= 0) {
			consumerModel.remove(index);
		}
		processSelection();
		validate();
	}

	void connectAll(boolean connect) {

		PluginTool producer = producerList.getSelectedValue();
		PluginTool consumer = consumerList.getSelectedValue();

		// clear the event list
		eventList.setModel(new DefaultListModel<>());

		if (consumer == null || producer == null) {
			return;
		}

		if (producer.getName().equals(consumer.getName())) {
			return;
		}

		ToolConnection tc = toolManager.getConnection(producer, consumer);
		// connect all producer to consumer
		eventNames = tc.getEvents();
		for (String eventName : eventNames) {
			doConnect(producer, consumer, eventName, connect);
		}

		// connect all consumer to producer
		tc = toolManager.getConnection(consumer, producer);
		eventNames = tc.getEvents();
		for (String eventName : eventNames) {
			doConnect(consumer, producer, eventName, connect);
		}

		updateDisplay();
	}

	///////////////////////////////////////////////////////////////
	// *** private methods ***
	///////////////////////////////////////////////////////////////

	private void initialize() {

		JPanel panel = createListPanel();
		// add sub-panels to the dialog
		setLayout(new BorderLayout(10, 10));
		add(panel, BorderLayout.CENTER);

		producerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		consumerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		eventList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		// add selection listeners for the producer and consumer lists
		ListSelectionModel sm = producerList.getSelectionModel();
		sm.addListSelectionListener(this);
		sm = consumerList.getSelectionModel();
		sm.addListSelectionListener(this);

		eventList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				processMouseClicked(e);
			}
		});
		eventList.setCellRenderer(new DataCellRenderer());
		producerList.setCellRenderer(
			GListCellRenderer.createDefaultTextRenderer(tool -> tool.getName()));
		consumerList.setCellRenderer(
			GListCellRenderer.createDefaultTextRenderer(tool -> tool.getName()));
		producerModel = (DefaultListModel<PluginTool>) producerList.getModel();
		consumerModel = (DefaultListModel<PluginTool>) consumerList.getModel();
	}

	private void processMouseClicked(MouseEvent e) {
		if (e.getClickCount() == 1) {
			JList<?> list = (JList<?>) e.getSource();
			int index = list.locationToIndex(e.getPoint());
			if (index < 0) {
				return;
			}
			if (!checkboxes[index].isEnabled()) {
				return;
			}

			boolean selected = checkboxes[index].isSelected();
			checkboxes[index].setSelected(!selected);
			refreshList(checkboxes);

			PluginTool producer = producerList.getSelectedValue();
			PluginTool consumer = consumerList.getSelectedValue();
			doConnect(producer, consumer, eventNames[index], !selected);

			int connectedCount = 0;
			for (JCheckBox checkboxe : checkboxes) {
				if (checkboxe.isSelected()) {
					connectedCount++;
				}
			}
			updateButtonEnablement(connectedCount);
		}
	}

	private void doConnect(PluginTool producer, PluginTool consumer, String eventName,
			boolean connect) {
		ToolConnection tc = toolManager.getConnection(producer, consumer);
		if (tc.isConnected(eventName) == connect) {
			// if already connected
			return;
		}

		if (connect) {
			tc.connect(eventName);
			Msg.info(this, msgSource + ": Connected consumer " + consumer.getName() +
				" to producer " + producer.getName() + " for event " + eventName);
		}
		else {
			tc.disconnect(eventName);
			Msg.info(this, msgSource + ": Disconnected consumer " + consumer.getName() +
				" from producer " + producer.getName() + " for event " + eventName);
		}
	}

	private void populateConsumerList() {

		consumerModel.removeAllElements();
		PluginTool[] tools = toolManager.getConsumerTools();

		Arrays.sort(tools, (t1, t2) -> {
			return t1.getName().compareTo(t2.getName());
		});

		for (PluginTool tool : tools) {
			consumerModel.addElement(tool);
		}
		if (tools.length == 0) {
			Msg.info(this, msgSource + ": No Tool consumes any events.");
		}
	}

	private void populateProducerList() {

		producerModel.removeAllElements();
		PluginTool[] tools = toolManager.getProducerTools();

		Arrays.sort(tools, (t1, t2) -> {
			return t1.getName().compareTo(t2.getName());
		});

		for (PluginTool tool : tools) {
			producerModel.addElement(tool);
		}
		if (tools.length == 0) {
			Msg.info(this, msgSource + ": No Tool generates events.");
		}
	}

	private void processSelection() {
		// clear the event list
		eventList.setModel(new DefaultListModel<>());

		PluginTool producer = producerList.getSelectedValue();
		if (producer == null) {
			toolDialog.setStatusText("Please select an Event Producer");
			return;
		}

		PluginTool consumer = consumerList.getSelectedValue();
		if (consumer == null) {
			toolDialog.setStatusText("Please select an Event Consumer");
			return;
		}

		if (producer.getName().equals(consumer.getName())) {
			toolDialog.setStatusText("The selected Event Producer Consumer must be different");
			return;
		}

		ToolConnection tc = toolManager.getConnection(producer, consumer);
		eventNames = tc.getEvents();
		checkboxes = new GCheckBox[eventNames.length];

		int connectedCount = 0;

		for (int i = 0; i < checkboxes.length; i++) {

			checkboxes[i] = new GCheckBox(eventNames[i]);
			checkboxes[i].setBackground(Colors.BACKGROUND);

			boolean isConnected = tc.isConnected(eventNames[i]);

			checkboxes[i].setSelected(isConnected);
			checkboxes[i].setEnabled(true);

			if (isConnected) {
				connectedCount++;
			}
		}
		refreshList(checkboxes);

		updateButtonEnablement(connectedCount);

		toolDialog.setStatusText("Please select on the events to be connected or disconnected");
	}

	private void updateButtonEnablement(int connectedCount) {
		toolDialog.setConnectAllEnabled(connectedCount < eventNames.length);
		toolDialog.setDisconnectAllEnabled(connectedCount > 0);
	}

	/**
	 * Clear selection on all lists.
	 */
	private void clearSelection() {
		consumerList.clearSelection();
		producerList.clearSelection();
		eventList.clearSelection();
	}

	/**
	 * replaces the list contents with the new list.
	 */
	private void refreshList(JCheckBox[] dataList) {
		eventList.setListData(dataList);
		eventList.clearSelection();
	}

	private JPanel createListPanel() {

		consumerList = new JList<>(new DefaultListModel<>());
		consumerList.setName("Consumers");
		consumerList.getAccessibleContext().setAccessibleName("Consumers");
		JScrollPane consumerListScrollPane = new JScrollPane(consumerList);

		producerList = new JList<>(new DefaultListModel<>());
		producerList.setName("Producers");
		producerList.getAccessibleContext().setAccessibleName("Producers");
		JScrollPane producerListScrollPane = new JScrollPane(producerList);

		eventList = new JList<>(new DefaultListModel<>());
		eventList.setName("Events");
		eventList.getAccessibleContext().setAccessibleName("Events");
		JScrollPane eventListScrollPane = new JScrollPane(eventList);

		Dimension minimumSize = new Dimension(150, 150);
		consumerListScrollPane.setMinimumSize(minimumSize);
		consumerListScrollPane.setPreferredSize(minimumSize);

		producerListScrollPane.setMinimumSize(minimumSize);
		producerListScrollPane.setPreferredSize(minimumSize);

		eventListScrollPane.setMinimumSize(minimumSize);
		eventListScrollPane.setPreferredSize(minimumSize);

		JPanel panel = new JPanel();
		GridBagLayout gbl = new GridBagLayout();
		panel.setLayout(gbl);

		JComponent[] row1 = null;
		JComponent[] row2 = null;

		JLabel producerLabel = new GDLabel("Event Producer:");
		producerLabel.getAccessibleContext().setAccessibleName("Event Producer");
		JLabel consumerLabel = new GDLabel("Event Consumer:");
		consumerLabel.getAccessibleContext().setAccessibleName("Event Consumer");
		JLabel eventLabel = new GDLabel("Event Names:");
		eventLabel.getAccessibleContext().setAccessibleName("Event Name");

		JComponent[] c1 = { producerLabel, consumerLabel, eventLabel };
		JComponent[] c2 = { producerListScrollPane, consumerListScrollPane, eventListScrollPane };

		row1 = c1;
		row2 = c2;

		GridBagConstraints gbc = null;

		for (int i = 0; i < row1.length; i++) {

			gbc = new GridBagConstraints();
			gbc.anchor = GridBagConstraints.NORTH;
			gbc.fill = GridBagConstraints.BOTH;
			gbc.gridx = i;
			gbc.gridy = 0;
			gbc.insets.top = 10;
			gbc.insets.left = 10;
			gbc.insets.right = 10;
			gbc.weightx = 1.0;
			gbl.setConstraints(row1[i], gbc);
			panel.add(row1[i]);
		}
		for (int i = 0; i < row2.length; i++) {

			gbc = new GridBagConstraints();
			gbc.anchor = GridBagConstraints.NORTH;
			gbc.fill = GridBagConstraints.BOTH;
			gbc.gridx = i;
			gbc.gridy = 1;
			gbc.insets.top = 5;
			gbc.insets.left = 10;
			gbc.insets.right = 10;
			gbc.weightx = 1.0;
			gbc.weighty = 1.0;
			gbl.setConstraints(row2[i], gbc);
			panel.add(row2[i]);
		}
		panel.getAccessibleContext().setAccessibleName("Tool Connection");
		return (panel);
	}

	private class DataCellRenderer implements ListCellRenderer<JCheckBox> {

		@Override
		public Component getListCellRendererComponent(JList<? extends JCheckBox> list,
				JCheckBox value, int index, boolean isSelected, boolean cellHasFocus) {

			if (index == -1) {
				int selected = list.getSelectedIndex();
				if (selected == -1) {
					return (null);
				}
				index = selected;
			}

			return (checkboxes[index]);
		}
	}

}
