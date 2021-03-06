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
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.Date;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;

import com.buddycloud.channeldirectory.commons.db.ChannelDirectoryDataSource;
import com.buddycloud.channeldirectory.commons.solr.SolrServerFactory;
import com.buddycloud.channeldirectory.search.handler.response.PostData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * @author Abmar
 *
 */
public class ActivityHelper {

	protected static final int MAX_WINDOW_SIZE = 30;
	
	private static Logger LOGGER = Logger.getLogger(ActivityHelper.class);
	
	private static String PUBLISHED_LABEL = "p";
	private static String ACTIVITY_LABEL = "a";
	
	private static final Long A_DAY = 24L * 60 * 60 * 1000;
	
	public static void updateActivity(PostData postData, 
			ChannelDirectoryDataSource dataSource, Properties properties) {
		updateActivity(postData, dataSource, properties, new SolrServerFactory());
	}
	
	/**
	 * @param postData
	 * @param dataSource
	 * @param configuration 
	 * @throws ParseException 
	 */
	public static void updateActivity(PostData postData, 
			ChannelDirectoryDataSource dataSource, Properties properties, 
			SolrServerFactory solrFactory) {
		
		if (postData == null) {
			throw new IllegalArgumentException("Post data can't be null.");
		}
		
		String channelJid = postData.getParentSimpleId();
		
		try {
			if (!isChannelRegistered(channelJid, solrFactory, properties)) {
				return;
			}
		} catch (Exception e1) {
			LOGGER.error("Could not retrieve channel info.", e1);
			return;
		}
		
		Long published = postData.getPublished().getTime();
		long thisPostPublishedInHours = published / A_DAY;
		
		ChannelActivity oldChannelActivity = null;
		try {
			oldChannelActivity = retrieveActivityFromDB(channelJid, dataSource);
		} catch (SQLException e) {
			return;
		}
		
		long summarizedActivity = 0;
		JsonObject[] channelHistory = new JsonObject[MAX_WINDOW_SIZE];
		
		for (int i = 0; i < MAX_WINDOW_SIZE; i++) {
			JsonObject activityobject = new JsonObject();
			activityobject.addProperty(PUBLISHED_LABEL, thisPostPublishedInHours - i);
			activityobject.addProperty(ACTIVITY_LABEL, 0);
			channelHistory[i] = activityobject;
		}
		
		if (oldChannelActivity != null) {
			JsonArray oldActivity = oldChannelActivity.activity;
			
			// Crawled already
			long oldestActivity = oldActivity.get(
					MAX_WINDOW_SIZE - 1).getAsJsonObject().get(PUBLISHED_LABEL).getAsLong();
			if (thisPostPublishedInHours < oldestActivity) {
				return;
			}
			
			JsonObject lastActivityInWindow = oldActivity.get(0).getAsJsonObject();
			long lastActivityPublishedInHours = lastActivityInWindow.get(PUBLISHED_LABEL).getAsLong();
			int hoursToAppend = (int) (thisPostPublishedInHours - lastActivityPublishedInHours);
			
			int startingIndex = Math.max(hoursToAppend, 0);
			
			for (int i = startingIndex; i < MAX_WINDOW_SIZE; i++) {
				JsonObject oldActivityOnIdx = (JsonObject) oldActivity.get(i);
				channelHistory[i] = oldActivityOnIdx;
			}
		}
		
		JsonObject lastActivityInWindow = channelHistory[0].getAsJsonObject();
		int thisIdx = (int) (lastActivityInWindow.get(
				PUBLISHED_LABEL).getAsLong() - thisPostPublishedInHours);
		long thisActivity = channelHistory[thisIdx].get(ACTIVITY_LABEL).getAsLong();
		channelHistory[thisIdx].addProperty(ACTIVITY_LABEL, thisActivity + 1);

		JsonArray newChannelHistory = new JsonArray();
		for (int i = 0; i < MAX_WINDOW_SIZE; i++) {
			JsonObject activityObject = channelHistory[i];
			summarizedActivity += activityObject.get(ACTIVITY_LABEL).getAsLong();
			newChannelHistory.add(activityObject);
		}
		
		if (oldChannelActivity == null) {
			insertActivityInDB(channelJid, newChannelHistory, summarizedActivity, 
					postData.getPublished(), dataSource);
		} else {
			updateActivityInDB(channelJid, newChannelHistory, summarizedActivity, 
					postData.getPublished(), dataSource);
		}
	}
	
	/**
	 * @param channelJid
	 * @param properties
	 * @return
	 */
	private static boolean isChannelRegistered(String channelJid,
			SolrServerFactory solrFactory, Properties properties) throws Exception {
		SolrServer solrServer = solrFactory.createChannelCore(properties);
		SolrQuery solrQuery = new SolrQuery("jid:" + channelJid);
		QueryResponse queryResponse = solrServer.query(solrQuery);
		return !queryResponse.getResults().isEmpty();
	}

	/**
	 * @param channelJid
	 * @param newChannelActivity
	 * @param summarizedActivity
	 * @param published
	 * @param dataSource 
	 * @throws SQLException 
	 */
	private static void insertActivityInDB(String channelJid,
			JsonArray newChannelActivity, long summarizedActivity,
			Date published, ChannelDirectoryDataSource dataSource) {
		PreparedStatement statement = null;
		try {
			Timestamp timestamp = new Timestamp(published.getTime());
			statement = dataSource.prepareStatement(
					"INSERT INTO channel_activity(channel_jid, detailed_activity, " +
					"summarized_activity, updated, earliest) " +
					"values (?, ?, ?, ?, ?)", 
					channelJid, newChannelActivity.toString(), summarizedActivity, 
					timestamp, timestamp);
			statement.execute();
		} catch (SQLException e) {
			LOGGER.error(e);
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}

	/**
	 * @param channelJid
	 * @param newChannelActivity
	 * @param summarizedActivity
	 * @param published
	 * @param dataSource 
	 * @throws SQLException 
	 */
	private static void updateActivityInDB(String channelJid,
			JsonArray newChannelActivity, long summarizedActivity,
			Date published, ChannelDirectoryDataSource dataSource) {
		PreparedStatement statement = null;
		try {
			Timestamp timestamp = new Timestamp(published.getTime());
			statement = dataSource.prepareStatement(
					"UPDATE channel_activity SET detailed_activity = ?, " +
					"summarized_activity = ?, updated = GREATEST(?, updated), earliest = LEAST(?, earliest) " +
					"WHERE channel_jid = ?", 
					newChannelActivity.toString(), summarizedActivity, 
					timestamp, timestamp, channelJid);
			statement.execute();
		} catch (SQLException e) {
			LOGGER.error(e);
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
		
	}

	/**
	 * @param channelJid
	 * @return
	 * @throws SQLException 
	 */
	private static ChannelActivity retrieveActivityFromDB(String channelJid, 
			ChannelDirectoryDataSource dataSource) throws SQLException  {
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT * FROM channel_activity WHERE channel_jid = ?", 
					channelJid);
			ResultSet resultSet = statement.executeQuery();
			if (resultSet.next()) {
				Date updated = new Date(resultSet.getTimestamp("updated").getTime());
				Date earliest = new Date(resultSet.getTimestamp("earliest").getTime());
				String detailedActivity = resultSet.getString("detailed_activity");
				JsonArray detailedActivityJson = new JsonParser().parse(detailedActivity).getAsJsonArray();
				return new ChannelActivity(detailedActivityJson, updated, earliest);
			}
			
			return null;
			
		} catch (SQLException e1) {
			LOGGER.error(e1);
			throw e1;
		} finally {
			ChannelDirectoryDataSource.close(statement);
		}
	}

	private static class ChannelActivity {
		
		private JsonArray activity;
		private Date updated;
		private Date earliest;
		
		public ChannelActivity(JsonArray activity, Date updated, Date earliest) {
			this.activity = activity;
			this.updated = updated;
			this.earliest = earliest;
		}
	}
}
