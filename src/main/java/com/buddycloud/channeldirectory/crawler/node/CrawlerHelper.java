/*
 * Copyright 2011 buddycloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.buddycloud.channeldirectory.crawler.node;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.log4j.Logger;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smackx.pubsub.BuddycloudNode;
import org.jivesoftware.smackx.pubsub.Item;
import org.jivesoftware.smackx.pubsub.PayloadItem;

import com.buddycloud.channeldirectory.commons.db.ChannelDirectoryDataSource;

/**
 *
 */
public class CrawlerHelper {

	private static Logger LOGGER = Logger.getLogger(CrawlerHelper.class);
	
	/**
	 * @param user
	 * @throws SQLException 
	 */
	public static void enqueueNewServer(String user, 
			ChannelDirectoryDataSource dataSource) {
		String server = user.substring(user.indexOf('@') + 1);
		insertServer(server, dataSource);
	}

	public static String getNodeId(String nodeFullJid) {
		String[] nodeFullJidSplitted = nodeFullJid.split("/");
		
		if (nodeFullJidSplitted.length < 4) {
			return null;
		}
		
		String nodeId = nodeFullJidSplitted[2];
		return nodeId;
	}

	public static void insertServer(String server, ChannelDirectoryDataSource dataSource) {
		
		if (CrawlerHelper.isServerSubscribed(server, dataSource)) {
			return;
		}
		
		PreparedStatement statement = null;
		
		try {
			statement = dataSource.prepareStatement(
					"INSERT INTO subscribed_server(name) values (?)", 
					server);
			statement.execute();
		} catch (SQLException e) {
			LOGGER.error("Could not insert server " + server, e);
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}

	private static boolean isServerSubscribed(String server, ChannelDirectoryDataSource dataSource) {
		
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT * FROM subscribed_server WHERE name = ?", 
					server);
			ResultSet resultSet = statement.executeQuery();
			
			return resultSet.next();
			
		} catch (SQLException e1) {
			LOGGER.error(e1);
			return false;
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
		
	}
	
	public static void insertNode(BuddycloudNode node, String server, ChannelDirectoryDataSource dataSource) {
		
		if (isNodeSubscribed(node, server, dataSource)) {
			return;
		}
		
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"INSERT INTO subscribed_node(name, server) values (?, ?)", 
					node.getId(), server);
			statement.execute();
		} catch (SQLException e1) {
			LOGGER.error("Could not insert node " + node + " " + server, e1);
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
		
	}
	
	public static void updateLastItemCrawled(BuddycloudNode node, String lastItemCrawled, 
			String server, ChannelDirectoryDataSource dataSource) {
		if (lastItemCrawled == null || 
				!isNodeSubscribed(node, server, dataSource)) {
			return;
		}
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"UPDATE subscribed_node SET last_item_crawled = ? " +
					"WHERE name = ? AND server = ?", 
					lastItemCrawled, node.getId(), server);
			statement.execute();
		} catch (SQLException e1) {
			LOGGER.error("Could not update last item crawled on " + 
					node + " at " + server, e1);
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}
	
	public static String getLastItemCrawled(BuddycloudNode node, String server, 
			ChannelDirectoryDataSource dataSource) {
		
		if (!isNodeSubscribed(node, server, dataSource)) {
			return null;
		}
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT last_item_crawled FROM subscribed_node " +
					"WHERE name = ? AND server = ?", 
					node.getId(), server);
			ResultSet resultSet = statement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getString("last_item_crawled");
			}
			return null;
		} catch (SQLException e1) {
			LOGGER.error("Could not retrieve last item crawled from " + 
					node + " at " + server, e1);
			return null;
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}
	
	public static String getLastItemCrawled(String server, 
			ChannelDirectoryDataSource dataSource) {
		
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT last_item_crawled FROM subscribed_node " +
					"WHERE server = ? ORDER BY items_crawled DESC LIMIT 1", 
					server);
			ResultSet resultSet = statement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getString("last_item_crawled");
			}
			return null;
		} catch (SQLException e1) {
			LOGGER.error("Could not retrieve last item crawled from " + server, e1);
			return null;
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}
	
	private static boolean isNodeSubscribed(BuddycloudNode node, String server, ChannelDirectoryDataSource dataSource) {
		
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT * FROM subscribed_node WHERE name = ? AND server = ?", 
					node.getId(), server);
			ResultSet resultSet = statement.executeQuery();
			
			return resultSet.next();
			
		} catch (SQLException e1) {
			LOGGER.error(e1);
			return false;
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
		
	}

	@SuppressWarnings("unchecked")
	public static Element getAtomEntry(Item item) throws DocumentException {
		PayloadItem<PacketExtension> payloadItem = (PayloadItem<PacketExtension>) item;
		PacketExtension payload = payloadItem.getPayload();
		
		Element atomEntry = DocumentHelper.parseText(
				payload.toXML().toString()).getRootElement();
		return atomEntry;
	}
	
	public static String getNodeFromItemId(String itemId) {
		return itemId.split(",")[1];
	}
	
	public static String getChannelFromNode(String node) {
		return node.split("/")[2];
	}

}
