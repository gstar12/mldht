package lbms.plugins.mldht.kad.messages;

import java.util.*;

import lbms.plugins.mldht.kad.*;
import lbms.plugins.mldht.kad.DHT.DHTtype;
import lbms.plugins.mldht.kad.DHT.LogLevel;
import lbms.plugins.mldht.kad.messages.MessageBase.Method;
import lbms.plugins.mldht.kad.messages.MessageBase.Type;

/**
 * @author Damokles
 * 
 */
public class MessageDecoder {

	public static MessageBase parseMessage (Map<String, Object> map,
			RPCServerBase srv) {

		try {
			String msgType = getStringFromBytes((byte[]) map.get(Type.TYPE_KEY));
			if (msgType == null) {
				return null;
			}

			String version = getStringFromBytes((byte[]) map.get(MessageBase.VERSION_KEY),true);

			MessageBase mb = null;
			if (msgType.equals(Type.REQ_MSG.getRPCTypeName())) {
				mb = parseRequest(map, srv);
			} else if (msgType.equals(Type.RSP_MSG.getRPCTypeName())) {
				mb = parseResponse(map, srv);
			} else if (msgType.equals(Type.ERR_MSG.getRPCTypeName())) {
				mb = parseError(map);
			}

			if (mb != null && version != null) {
				mb.setVersion(version);
			}

			return mb;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * @param map
	 * @return
	 */
	private static MessageBase parseError (Map<String, Object> map) {
		Object error = map.get(Type.ERR_MSG.innerKey());
		
		int errorCode = 0;
		String errorMsg = null;
		
		if(error instanceof byte[])
			errorMsg = getStringFromBytes((byte[])error);
		else if (error instanceof List<?>)
		{
			List<Object> errmap = (List<Object>)error;
			try
			{
				errorCode = ((Long) errmap.get(0)).intValue();
				errorMsg = getStringFromBytes((byte[]) errmap.get(1));
			} catch (Exception e)
			{
				// do nothing
			}
		}
		
		Object rawMtid = map.get(MessageBase.TRANSACTION_KEY);
		
		if (errorMsg == null || rawMtid == null || !(rawMtid instanceof byte[]))
			return null;

		byte[] mtid = (byte[]) rawMtid;
		if (mtid == null || mtid.length < 1)
			return null;

		return new ErrorMessage(mtid, errorCode,errorMsg);
	}

	/**
	 * @param map
	 * @param srv
	 * @return
	 */
	private static MessageBase parseResponse (Map<String, Object> map,RPCServerBase srv) {

		byte[] mtid = (byte[]) map.get(MessageBase.TRANSACTION_KEY);
		if (mtid == null || mtid.length < 1)
			return null;

		// find the call
		RPCCallBase c = srv.findCall(mtid);
		if (c == null) {
			DHT.logDebug("Cannot find RPC call for response: "
					+ new String(mtid));
			return null;
		}

		return parseResponse(map, c.getMessageMethod(), mtid,c);
	}

	/**
	 * @param map
	 * @param msgMethod
	 * @param mtid
	 * @return
	 */
	private static MessageBase parseResponse (Map<String, Object> map,
			Method msgMethod, byte[] mtid,RPCCallBase base) {
		Map<String, Object> args = (Map<String, Object>) map.get(Type.RSP_MSG.innerKey());
		if (args == null || !args.containsKey("id")) {
			return null;
		}

		byte[] hash = (byte[]) args.get("id");

		if (hash == null || hash.length != Key.SHA1_HASH_LENGTH) {
			return null;
		}

		Key id = new Key(hash);
		
		MessageBase msg = null;

		switch (msgMethod) {
		case PING:
			msg = new PingResponse(mtid);
			break;
		case ANNOUNCE_PEER:
			msg = new AnnounceResponse(mtid);
			break;
		case FIND_NODE:
			if (!args.containsKey("nodes") && !args.containsKey("nodes6"))
				return null;
			
			msg = new FindNodeResponse(mtid, (byte[]) args.get("nodes"),(byte[])args.get("nodes6"));
			break;
		case GET_PEERS:
			byte[] token = (byte[]) args.get("token");
			byte[] nodes = (byte[]) args.get("nodes");
			byte[] nodes6 = (byte[]) args.get("nodes6");
			
			List<byte[]> vals = (List<byte[]>) args.get("values");
			List<DBItem> dbl = null;
			if (vals != null && vals.size() > 0)
			{
				dbl = new ArrayList<DBItem>(vals.size());
				for (int i = 0; i < vals.size(); i++)
				{
					// only accept ipv4 or ipv6 for now
					if (vals.get(i).length != DHTtype.IPV4_DHT.ADDRESS_ENTRY_LENGTH && vals.get(i).length != DHTtype.IPV6_DHT.ADDRESS_ENTRY_LENGTH)
						continue;
					dbl.add(new PeerAddressDBItem((byte[]) vals.get(i), false));
				}
			}

			if (dbl != null || nodes != null || nodes6 != null)
			{
				GetPeersResponse resp = new GetPeersResponse(mtid, nodes, nodes6, token);
				resp.setPeerItems(dbl);
				msg = resp; 
				break;
			}
			DHT.logDebug("No nodes or values in get_peers response");
			return null;
 
		default:
			return null;
		}
		
		msg.setID(id);
		
		return msg;
	}

	/**
	 * @param map
	 * @return
	 */
	private static MessageBase parseRequest (Map<String, Object> map, RPCServerBase srv) {
		Object rawRequestMethod = map.get(Type.REQ_MSG.getRPCTypeName());
		Map<String, Object> args = (Map<String, Object>) map.get(Type.REQ_MSG.innerKey());
		
		if (rawRequestMethod == null || args == null)
			return null;

		byte[] mtid = (byte[])map.get(MessageBase.TRANSACTION_KEY);
		byte[] hash = (byte[]) args.get("id");
		
		if (mtid == null || mtid.length < 1 || hash == null || hash.length != Key.SHA1_HASH_LENGTH)
			return null;

		Key id = new Key(hash);

		MessageBase msg = null;

		String requestMethod = getStringFromBytes((byte[]) rawRequestMethod);
		if (Method.PING.getRPCName().equals(requestMethod)) {
			msg = new PingRequest();
		} else if (Method.FIND_NODE.getRPCName().equals(requestMethod) || Method.GET_PEERS.getRPCName().equals(requestMethod)) {
			hash = (byte[]) args.get("target");
			if (hash == null)
				hash = (byte[]) args.get("info_hash");
			if (hash == null || hash.length != Key.SHA1_HASH_LENGTH)
			{
				return null;
			}
			AbstractLookupRequest req = Method.FIND_NODE.getRPCName().equals(requestMethod) ? new FindNodeRequest(new Key(hash)) : new GetPeersRequest(new Key(hash));
			req.setWant4(srv.getDHT().getType() == DHTtype.IPV4_DHT);
			req.setWant6(srv.getDHT().getType() == DHTtype.IPV6_DHT);
			req.decodeWant((List<byte[]>) args.get("want"));
			if (req instanceof GetPeersRequest)
			{
				GetPeersRequest peerReq = (GetPeersRequest) req;
				peerReq.setNoSeeds(Long.valueOf(1).equals(args.get("noseed")));
				peerReq.setScrape(Long.valueOf(1).equals(args.get("scrape")));
			}
			msg = req;
		} else if (Method.ANNOUNCE_PEER.getRPCName().equals(requestMethod)) {
			if (args.containsKey("info_hash") && args.containsKey("port")
					&& args.containsKey("token")) {
				hash = (byte[]) args.get("info_hash");
				if (hash == null || hash.length != Key.SHA1_HASH_LENGTH) {
					return null;
				}
				Key infoHash = new Key(hash);

				byte[] token = (byte[]) args.get("token");
				
				AnnounceRequest ann = new AnnounceRequest(infoHash, ((Long) args.get("port")).intValue(), token);
				ann.setSeed(Long.valueOf(1).equals(args.get("seed")));

				msg = ann;
			}
		} else {
			DHT.logDebug("Received unknown Message Type: " + requestMethod);
		}

		if (msg != null) {
			msg.setMTID(mtid);
			msg.setID(id);
		}

		return msg;
	}
	
	private static String getStringFromBytes (byte[] bytes, boolean preserveBytes) {
		if (bytes == null) {
			return null;
		}
		try {
			return new String(bytes, preserveBytes ? "ISO-8859-1" : "UTF-8");
		} catch (Exception e) {
			DHT.log(e, LogLevel.Verbose);
			return null;
		}
	}

	private static String getStringFromBytes (byte[] bytes) {
		return getStringFromBytes(bytes, false);
	}
}
