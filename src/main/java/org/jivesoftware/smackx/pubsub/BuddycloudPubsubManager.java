package org.jivesoftware.smackx.pubsub;

import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.pubsub.packet.PubSubNamespace;

public class BuddycloudPubsubManager {

	static {
		// Use the BuddycloudAffiliationsProvider
		BuddycloudAffiliationsProvider affiliationsProvider = new BuddycloudAffiliationsProvider();
		ProviderManager.addExtensionProvider(BuddycloudAffiliations.ELEMENT_NAME, 
				PubSubNamespace.OWNER.getXmlns(), affiliationsProvider);
		ProviderManager.addExtensionProvider(BuddycloudAffiliations.ELEMENT_NAME, 
				PubSubNamespace.BASIC.getXmlns(), affiliationsProvider);
		
		// Use the BuddycloudAffiliationProvider for buddycloud-specific affiliations
		BuddycloudAffiliationProvider affiliationProvider = new BuddycloudAffiliationProvider();
		ProviderManager.addExtensionProvider(BuddycloudAffiliation.ELEMENT_NAME, 
				PubSubNamespace.OWNER.getXmlns(), affiliationProvider);
		ProviderManager.addExtensionProvider(BuddycloudAffiliation.ELEMENT_NAME, 
				PubSubNamespace.BASIC.getXmlns(), affiliationProvider);
	}
	
	private final PubSubManager manager;
	private XMPPConnection connection;
	private final String toAddress;
	
	public BuddycloudPubsubManager(XMPPConnection connection, String toAddress) {
		this.manager = new PubSubManager(connection, toAddress);
		this.connection = connection;
		this.toAddress = toAddress;
	}

	public BuddycloudNode getNode(String id) throws NoResponseException,
			XMPPErrorException, NotConnectedException {
		return new BuddycloudNode(manager.getNode(id));
	}

	public DiscoverItems discoverNodes(String nodeId)
			throws NoResponseException, XMPPErrorException,
			NotConnectedException {
		return manager.discoverNodes(nodeId);
	}

	public BuddycloudNode getFirehoseNode() {
		BuddycloudFirehoseNode firehose = new BuddycloudFirehoseNode(connection);
		firehose.setTo(toAddress);
		return new BuddycloudNode(firehose);
	}
}
