package com.buddycloud.channeldirectory.handler.recommendation;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Properties;

import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.solr.client.solrj.SolrServerException;
import org.dom4j.Element;
import org.xmpp.packet.IQ;

import com.buddycloud.channeldirectory.handler.ChannelQueryHandler;
import com.buddycloud.channeldirectory.handler.response.ChannelData;
import com.buddycloud.channeldirectory.utils.XMPPUtils;

/**
 * Handles queries for user recommendation.
 * A query should contain an user jid, so
 * this handler can return recommended channels 
 * to this given user.
 *  
 */
public class RecommendationQueryHandler extends ChannelQueryHandler {

	private final ChannelRecommender recommender;
	
	public RecommendationQueryHandler(Properties properties) {
		super("http://buddycloud.com/channel_directory/recommendation_query", properties);
		try {
			this.recommender = new ChannelRecommender();
		} catch (TasteException e) {
			getLogger().fatal(e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public IQ handle(IQ iq) {
		
		Element queryElement = iq.getElement().element("query");
		Element userJidElement = queryElement.element("user-jid");
		
		if (userJidElement == null) {
			return XMPPUtils.error(iq, "Query does not contain user-jid element.", 
					getLogger());
		}
		
		String userJid = userJidElement.getText();
		
		if (userJid == null || userJid.isEmpty()) {
			return XMPPUtils.error(iq, "User-jid cannot be empty.", 
					getLogger());
		}
		
		List<ChannelData> recommendedChannels;
		try {
			recommendedChannels = findRecommendedChannels(userJid);
		} catch (Exception e) {
			return XMPPUtils.error(iq, "Search could not be performed, service is unavailable.", 
					getLogger());
		}
		
		return createIQResponse(iq, recommendedChannels);
	}

	private List<ChannelData> findRecommendedChannels(String search)
			throws MalformedURLException, SolrServerException, TasteException {
		return recommender.recommend(search, 10);
	}
}
