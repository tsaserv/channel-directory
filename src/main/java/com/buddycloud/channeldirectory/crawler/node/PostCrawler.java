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

import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smackx.pubsub.BuddycloudNode;
import org.jivesoftware.smackx.pubsub.Item;
import org.jivesoftware.smackx.pubsub.Node;
import org.jivesoftware.smackx.rsm.packet.RSMSet;

import com.buddycloud.channeldirectory.commons.db.ChannelDirectoryDataSource;
import com.buddycloud.channeldirectory.commons.solr.SolrServerFactory;
import com.buddycloud.channeldirectory.search.handler.response.Geolocation;
import com.buddycloud.channeldirectory.search.handler.response.PostData;

/**
 * Responsible for crawling {@link Node} data
 * regarding its posts.
 *  
 */
public class PostCrawler implements NodeCrawler {

	/**
	 * 
	 */
	private static final DecimalFormat LATLNG_FORMAT = new DecimalFormat("#0.00", 
			new DecimalFormatSymbols(Locale.US));
	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
	private static Logger LOGGER = Logger.getLogger(PostCrawler.class);
	
	private Properties configuration;
	private final ChannelDirectoryDataSource dataSource;
	
	public PostCrawler(Properties configuration, ChannelDirectoryDataSource dataSource) {
		this.configuration = configuration;
		this.dataSource = dataSource;
	}
	
	/* (non-Javadoc)
	 * @see com.buddycloud.channeldirectory.crawler.node.NodeCrawler#crawl(org.jivesoftware.smackx.pubsub.Node)
	 */
	@Override
	public void crawl(BuddycloudNode node, String server) throws Exception {
		
		// /user/node@domain/posts
		String nodeId = node.getId();
		// node@domain
		String channelId = CrawlerHelper.getChannelFromNode(nodeId);
		
		String afterItem = CrawlerHelper.getLastItemCrawled(
				node, server, dataSource);
				
		String olderItemId = null;
		String mostRecentItemId = null;
		
		while (true) {
			List<PacketExtension> additionalExtensions = new LinkedList<PacketExtension>();
			List<PacketExtension> returnedExtensions = new LinkedList<PacketExtension>();
			List<Item> items = null;
			if (olderItemId != null) {
				RSMSet nextRsmSet = RSMSet.newAfter(olderItemId);
				additionalExtensions.add(nextRsmSet);
			}
			items = node.getItems(additionalExtensions, returnedExtensions);
			if (items.isEmpty()) {
				break;
			}
			boolean done = false;
			for (Item item : items) {
				Element itemEntry = CrawlerHelper.getAtomEntry(item);
				String itemId = itemEntry.elementText("id");
				if (itemId.equals(afterItem)) {
					done = true;
					break;
				}
				if (mostRecentItemId == null) {
					mostRecentItemId = itemId;
				}
				olderItemId = itemId;
				try {
					processPost(nodeId, channelId, item);
				} catch (Exception e) {
					LOGGER.warn(e);
				}
			}
			if (done) {
				break;
			}
		}
		CrawlerHelper.updateLastItemCrawled(node, 
				mostRecentItemId, server, dataSource);
	}

	void processPost(String nodeId, String channel, Item item)
			throws Exception {
		PostData postData = saveToSolr(nodeId, channel, item);
		ActivityHelper.updateActivity(postData, dataSource, configuration);
	}

	private PostData saveToSolr(String nodeFullJid, String nodeId, Item item)
			throws DocumentException, ParseException, SolrServerException,
			IOException {
		Element atomEntry = CrawlerHelper.getAtomEntry(item);
		
		PostData postData = new PostData();
		
		postData.setParentFullId(nodeFullJid);
		postData.setParentSimpleId(nodeId);
		
		Element authorElement = atomEntry.element("author");
		String authorName = authorElement.elementText("name");
		String authorUri = authorElement.elementText("uri");
		
		postData.setAuthor(authorName);
		postData.setAuthorURI(authorUri);
		
		String content = atomEntry.elementText("content");
		String updated = atomEntry.elementText("updated");
		String published = atomEntry.elementText("published");
		String id = atomEntry.elementText("id");
		
		postData.setContent(content);
		postData.setUpdated(DATE_FORMAT.parse(updated));
		postData.setPublished(DATE_FORMAT.parse(published));
		postData.setId(id);
		
		Element geolocElement = atomEntry.element("geoloc");
		
		if (geolocElement != null) {
			Geolocation geolocation = new Geolocation();
			
			String text = geolocElement.elementText("text");
			geolocation.setText(text);
			
			String lat = geolocElement.elementText("lat");
			String lon = geolocElement.elementText("lon");
			
			if (lat != null && lon != null) {
				geolocation.setLat(Double.valueOf(lat));
				geolocation.setLng(Double.valueOf(lon));
			}
			
			postData.setGeolocation(geolocation);
		}
		
		Element inReplyToElement = atomEntry.element("in-reply-to");
		if (inReplyToElement != null) {
			String replyRef = inReplyToElement.attributeValue("ref");
			postData.setInReplyTo(replyRef);
		}
		insert(postData);
		return postData;
	}

	private void insert(PostData postData) throws SolrServerException,
			IOException {
		
		SolrInputDocument postDocument = new SolrInputDocument();
		
		postDocument.addField("id", postData.getId());
		postDocument.addField("parent_simpleid", postData.getParentSimpleId());
		postDocument.addField("parent_fullid", postData.getParentFullId());
		postDocument.addField("inreplyto", postData.getInReplyTo());
		postDocument.addField("author", postData.getAuthor());
		postDocument.addField("author-uri", postData.getAuthorUri());
		postDocument.addField("content", postData.getContent());

		postDocument.addField("updated", postData.getUpdated());
		postDocument.addField("published", postData.getPublished());

		Geolocation geolocation = postData.getGeolocation();
		
		if (geolocation != null) {
			
			Double lat = geolocation.getLat();
			Double lng = geolocation.getLng();
			
			if (lat != null && lng != null) {
				postDocument.setField("geoloc", LATLNG_FORMAT.format(lat) + ","
						+ LATLNG_FORMAT.format(lng));
			}
			
			if (geolocation.getText() != null) {
				postDocument.addField("geoloc_text", geolocation.getText());
			}
		}
		
		SolrServer solrServer = new SolrServerFactory().createPostCore(configuration);
		solrServer.add(postDocument);
		solrServer.commit();
	}

	/* (non-Javadoc)
	 * @see com.buddycloud.channeldirectory.crawler.node.NodeCrawler#accept(org.jivesoftware.smackx.pubsub.Node)
	 */
	@Override
	public boolean accept(BuddycloudNode node) {
		return node.getId().endsWith("/posts");
	}
}
