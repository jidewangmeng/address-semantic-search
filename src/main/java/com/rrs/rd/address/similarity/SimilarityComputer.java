package com.rrs.rd.address.similarity;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rrs.rd.address.interpret.AddressInterpreter;
import com.rrs.rd.address.persist.AddressEntity;
import com.rrs.rd.address.persist.RegionEntity;
import com.rrs.rd.address.persist.RegionType;
import com.rrs.rd.address.similarity.segment.SimpleSegmenter;
import com.rrs.rd.address.utils.StringUtil;

/**
 * 相似度算法相关逻辑。
 *
 * <p>
 * <strong>关于标准TF-IDF算法</strong>：<br />
 * TC: 词数 Term Count，某个词在文档中出现的次数。<br />
 * TF: 词频 Term Frequency, 某个词在文档中出现的频率，TF = 该词在文档中出现的次数 / 该文档的总词数。<br />
 * IDF: 逆文档词频 Inverse Document Frequency，IDF = log( 文档总数 / ( 包含该词的文档数 + 1 ) )。分母加1是为了防止分母出现0的情况。<br />
 * TF-IDF: 词条的特征值，TF-IDF = TF * IDF。 
 * </p>
 * 
 * <p>
 * <strong>基于标准TF-IDF算法的调整</strong>：<br />
 * 计算地址相似度时，将标准TF-IDF算法中的词频TF改为了自定义的词条权重值，因为：<br />
 * 1. 地址不同于文章，文章是纯自然语言，而地址是格式规范性较强的短语；<br />
 * 2. 地址的特征并不是通过特征词的重复得以强化，而是通过特定组成部分强化的，例如道路名+门牌号、小区名等；<br />
 * </p>
 * 
 * <p>
 * <strong>地址相似度计算逻辑</strong>：<br />
 * 1. 对地址解析后剩余的，无法进一步解析的{@link AddressEntity#getText() text}进行分词，这部分词条采用正常权重值；<br />
 * 2. 在词条列表的前面按次序添加省份、地级市、区县、街道/乡镇、村庄、道路、门牌号等词条，这部分词条采用自定义权重值；<br />
 * &nbsp;&nbsp;&nbsp;&nbsp;采用权重高值部分：省份、地级市、区县、乡镇、村庄、道路<br />
 * &nbsp;&nbsp;&nbsp;&nbsp;采用权重中值部分：门牌号<br />
 * &nbsp;&nbsp;&nbsp;&nbsp;采用权重低值部分：街道<br />
 * 步骤1、2由方法{@link #analyse(AddressEntity)}完成。<br />
 * 3. 在全部文档中为所有词条统计逆文档引用情况，由方法{@link #statInverseDocRefers(List)}完成；<br />
 * 4. 为文档中的每个词条计算特征值，由方法{@link #computeTermEigenvalue(Document, int, Map)}完成；<br />
 * 5. 为两个文档计算余弦相似度，由方法{@link #computeDocSimilarity1(Document, Document)}完成；<br />
 * &nbsp;&nbsp;&nbsp;&nbsp;文档特征向量的维度，取两个文档汇总后的独立词条个数。
 * </p>
 * 
 * <p>
 * <strong>使用相似度搜索相似地址的运行机制</strong>：<br />
 * 1. TODO
 * </p>
 * 
 * @author Richie 刘志斌 yudi@sina.com
 * 2016年9月21日
 */
public class SimilarityComputer {
	private final static Logger LOG = LoggerFactory.getLogger(SimilarityComputer.class);
	
	private static String DEFAULT_CACHE_FOLDER = "~/.vector_cache";
	private static double BOOST_M = 1; //正常权重
	private static double BOOST_L = 2; //加权高值
	private static double BOOST_XL = 3; //加权高值
	private static double BOOST_S = 0.5; //降权
	
	private static double MISSING_IDF = 4;
	
	private AddressInterpreter interpreter = null;
	private Segmenter segmenter = new SimpleSegmenter();
	private List<String> defaultTokens = new ArrayList<String>(0);
	private String cacheFolder;
	private boolean cacheVectorsInMemory = false;
	private static Map<String, List<Document>> VECTORS_CACHE = new HashMap<String, List<Document>>();
	private static Map<String, Map<String, Double>> IDF_CACHE = new HashMap<String, Map<String, Double>>();
	
	/**
	 * 分词，设置词条权重。
	 * @param addresses
	 * @return
	 */
	public List<Document> analyse(List<AddressEntity> addresses){
		if(addresses==null || addresses.isEmpty()) return null;
		
		List<Document> docs = new ArrayList<Document>(addresses.size());
		for(AddressEntity addr : addresses){
			docs.add(analyse(addr));
		}
		
		return docs;
	}
	
	/**
	 * 分词，设置词条权重。
	 * @param addr
	 * @return
	 */
	public Document analyse(AddressEntity addr){
		Document doc = new Document(addr.getId());
		//1. 分词。仅针对AddressEntity的text（地址解析后剩余文本）进行分词。
		List<String> tokens = defaultTokens;
		if(addr.getText().length()>0){
			tokens = segmenter.segment(addr.getText());
		}
		
		//2. 生成term
		//Set<String> doneTokens = new HashSet<String>(tokens.size()+6);
		List<Term> terms = new ArrayList<Term>(tokens.size()+6);
		//2.1 地址解析后已经识别出来的部分，直接作为词条生成Term。包括：省、地级市、区县、街道/镇/乡、村、道路、门牌号(roadNum)。
		//省市区如果匹配不准确，结果误差就很大，因此加大省市区权重。但实际上计算IDF时省份、城市的IDF基本都为0。
//		if(addr.hasProvince()) 
//			addTerm(addr.getProvince().getName(), TermType.Province, terms, addr.getProvince());
//		if(addr.hasCity()) 
//			addTerm(addr.getCity().getName(), TermType.City, terms, addr.getCity());
		if(addr.hasCounty()) 
			addTerm(addr.getCounty().getName(), TermType.County, terms, addr.getCounty());
		String residentDistrict = null, town = null;
		for(int i=0; addr.getTowns()!=null && i<addr.getTowns().size(); i++){
			if(addr.getTowns().get(i).endsWith("街道")) {
				residentDistrict = addr.getTowns().get(i);
				if(town==null) continue;
				else break;
			}
			if(addr.getTowns().get(i).endsWith("镇") || addr.getTowns().get(i).endsWith("乡")){
				town = StringUtil.rtrim(addr.getTowns().get(i), '镇', '乡');
				if(residentDistrict==null) continue;
				else break;
			}
		}
		//街道准确率低，不参与匹配
//		if(residentDistrict!=null) //街道准确率很低，很多人随便选择街道，因此将街道权重降低
//			addTerm(residentDistrict, TermType.Street, terms, null);
		if(town!=null) //目前情况下，对农村地区，物流公司的片区规划粒度基本不可能比乡镇更小，因此加大乡镇权重
			addTerm(town, TermType.Town, terms, null);
		if(!addr.getVillage().isEmpty()) //同上，村庄的识别度比较高，加大权重
			addTerm(addr.getVillage(), TermType.Village, terms, null);
		Term roadTerm = null;
		if(!addr.getRoad().isEmpty()) { //对于城市地址，道路识别度比较高，加大权重
			roadTerm = addTerm(addr.getRoad(), TermType.Road, terms, null);
			if(!TermType.Road.equals(roadTerm.getType())){
				LOG.warn("[addr] [find-similar] add road term failed: " + doc.getId() + ", " + addr.getRoad());
				roadTerm = null;
			}
		}
		//两个地址在道路(road)一样的情况下，门牌号(roadNum)的识别作用就非常大，但如果道路不一样，则门牌号的识别作用就很小。
		//为了强化门牌号的作用，但又需要避免产生干扰，因此将门牌号的权重设置为一个中值，而不是高值。
		if(!addr.getRoadNum().isEmpty()) {
			Term roadNumTerm = addTerm(addr.getRoadNum(), TermType.RoadNum, terms, null);
			if(TermType.RoadNum.equals(roadNumTerm.getType())){
				roadNumTerm.setRef(roadTerm);
			}
		}
		//2.2 地址文本分词后的token
		for(String token : tokens)
			addTerm(token, TermType.Text, terms, null);
		
		Map<String, Double> idfs = IDF_CACHE.get(this.buildCacheKey(addr));
		if(idfs!=null){
			for(Term t : terms){
				Double idf = idfs.get(t.getText());
				if(idf==null) t.setIdf(MISSING_IDF);
				else t.setIdf(idf.doubleValue());
			}
		}
		
		doc.setTerms(terms);
		
		return doc;
	}
	
	/**
	 * 为所有文档的全部词条统计逆向引用情况。
	 * @param docs 所有文档。
	 * @return 全部词条的逆向引用情况，key为词条，value为该词条在多少个文档中出现过。
	 */
	private Map<String, Integer> statInverseDocRefers(List<Document> docs){
		Map<String, Integer> idrc = new HashMap<String, Integer>(); 
		if(docs==null) return idrc;
		for(Document doc : docs) {
			if(doc.getTerms()==null) continue;
			for(Term term : doc.getTerms()){
				if(idrc.containsKey(term.getText()))
					idrc.put(term.getText(), idrc.get(term.getText()) + 1);
				else 
					idrc.put(term.getText(), 1);
			}
		}
		return idrc;
	}
	
	/**
	 * 计算词条加权权重boost值。
	 * @param forDoc true:为地址库文档词条计算boost；false:为查询文档词条计算boost。
	 * @param queryDoc 查询文档。
	 * @param queryTerm 查询文档词条。
	 * @param doc 地址库文档。
	 * @param term 地址库文档词条。
	 * @return
	 */
	private double getBoostValue(boolean forDoc, Document queryDoc, Term queryTerm, Document doc, Term term){
		//forDoc==true, 为地址库文档计算boost，queryDoc, doc, term肯定不为null，但queryTerm可能为null（执行explain时）；
		//forDoc==false, 为查询文档计算boost，queryDoc, queryTerm肯定不为null，doc, term肯定是null；
		if(forDoc && TermType.RoadNum.equals(term.getType())){
			//道路门牌号特殊处理
			Term queryRoadNumTerm = null, roadTerm = term.getRef();;
			for(Term qt : queryDoc.getTerms()){
				if(TermType.RoadNum.equals(qt.getType())){
					queryRoadNumTerm = qt;
					continue;
				}
			}
			Term queryRoadTerm = queryRoadNumTerm == null ? null : queryRoadNumTerm.getRef();
			if(queryRoadTerm!=null && roadTerm!=null && queryRoadTerm.equals(roadTerm)){
				//道路一样，根据门牌号间隔情况动态加权
				if(queryRoadNumTerm!=null){
					int queryRoadNum = translateRoadNum(queryRoadNumTerm.getText());
					int docRoadNum = translateRoadNum(term.getText());
					if(queryRoadNum>0 && docRoadNum>0){
						return ( 1 / Math.sqrt(Math.sqrt( Math.abs(queryRoadNum - docRoadNum) + 1 )) ) * BOOST_L;
					}
				}
				//道路号一样，但无法根据门牌号间隔加权，则门牌号降权
				//这会将机会让给那些可以按门牌号间隔加权的文档优先匹配（包括门牌号数字一样的情况）
				return BOOST_S;
			}
			return 0; //与查询文档道路不一样，则对门牌号降权处理
		}
		double value = BOOST_M;
		TermType type = forDoc ? term.getType() : queryTerm.getType();
		switch(type){
			case Province:
			case City:
			case County:
				value = BOOST_XL; //省市区、道路出现频次高，IDF值较低，因此给予比较高的加权权重
				break;
			case Town:
			case Village:
			case Road:
				value = BOOST_L; //乡镇、村庄出现频次低，本身IDF值高，因此给予中等加权
				break;
			case Street: 
				value = BOOST_S; //一般人对于城市街道范围概念不强，在地址中随意选择街道的可能性较高，因此降权处理
				break;
			case RoadNum:
				value = BOOST_L; //仅查询文档会执行此处逻辑，地址库文档的门牌号已经在上面处理完毕
				break;
			case Text:
				value = BOOST_M;
				break;
			default:
		}
		return value;
	}
	
	/**
	 * 将道路门牌号中的数字提取出来。
	 * @param text 道路门牌号，例如40号院、甲一号院等。
	 * @return 返回门牌号数字。
	 */
	public int translateRoadNum(String text){
		if(text==null || text.isEmpty()) return 0;
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<text.length(); i++){
			char c = text.charAt(i);
			if(c>='0' && c<='9') {
				sb.append(c);
				continue;
			}
			switch(c){
				case '０': sb.append(0); continue;
				case '１': sb.append(1); continue;
				case '２': sb.append(2); continue;
				case '３': sb.append(3); continue;
				case '４': sb.append(4); continue;
				case '５': sb.append(5); continue;
				case '６': sb.append(6); continue;
				case '７': sb.append(7); continue;
				case '８': sb.append(8); continue;
				case '９': sb.append(9); continue;
			}
		}
		if(sb.length()>0) return Integer.parseInt(sb.toString());
		boolean isTen = false;
		for(int i=0; i<text.length(); i++){
			char c = text.charAt(i);
			if(isTen){
				boolean pre = sb.length() > 0 ? true : false;
				boolean post = (c=='一' || c=='二' || c=='三' || c=='四' || c=='五' || c=='六' || c=='七' || c=='八' || c=='九') ?
					true : false;
				if(pre){
					if(post) { /*do nothing*/ }
					else { sb.append('0'); }
				}else{
					if(post) { sb.append('1'); }
					else { sb.append("10"); }
				}
				isTen = false;
			}
			switch(c){
				case '一': sb.append(1); continue;
				case '二': sb.append(2); continue;
				case '三': sb.append(3); continue;
				case '四': sb.append(4); continue;
				case '五': sb.append(5); continue;
				case '六': sb.append(6); continue;
				case '七': sb.append(7); continue;
				case '八': sb.append(8); continue;
				case '九': sb.append(9); continue;
				case '十':
					isTen = true;
					continue;
			}
			if(sb.length()>0) break;
		}
		if(isTen){
			if(sb.length()>0) sb.append('0');
			else sb.append("10");
		}
		if(sb.length()>0) return Integer.parseInt(sb.toString());
		return 0;
	}
	
	/**
	 * 将Document对象序列化成缓存格式。
	 * @param doc
	 * @return
	 */
	public String serialize(Document doc){
		StringBuilder sb = new StringBuilder();
		sb.append(doc.getId()).append('$');
		for(int i=0; i<doc.getTerms().size(); i++){
			Term term = doc.getTerms().get(i);
			if(i>0) sb.append('|');
			sb.append(term.getType().getValue()).append(term.getText());
		}
		return sb.toString();
	}
	
	/**
	 * 将缓存中的文档反序列化成Document对象。
	 * @param str
	 * @return
	 */
	public Document deserialize(String str){
		if(str==null || str.trim().isEmpty()) return null;
		String[] t1 = str.trim().split("\\$");
		if(t1.length!=2) return null;
		Document doc = new Document(Integer.parseInt(t1[0]));
		String[] t2 = t1[1].split("\\|");
		if(t2.length<=0) return doc;
		List<Term> terms = new ArrayList<Term>(t2.length);
		for(String termStr : t2){
			Term term = new Term(TermType.toEnum(termStr.charAt(0)), StringUtil.substring(termStr, 1));
			terms.add(term);
		}
		doc.setTerms(terms);
		
		return doc;
	}
	
	
	
	
	/**
	 * 搜索相似地址。
	 * @param addressText 详细地址文本，开头部分必须包含省、市、区。
	 * @param topN 返回多少条最相似地址。
	 * @return
	 */
	public Query findSimilarAddress(String addressText, int topN, int mode){
		long start = System.currentTimeMillis(), startCompute = 0, elapsedCompute = 0;
		Query query = new Query(topN);
		
		//解析地址
		if(addressText==null || addressText.trim().isEmpty())
			throw new IllegalArgumentException("Null or empty address text! Please provider a valid address.");
		AddressEntity queryAddr = interpreter.interpret(addressText);
		if(queryAddr==null){
			LOG.warn("[addr] [find-similar] [addr-err] null << " + addressText);
			throw new RuntimeException("Can't interpret address!");
		}
		if(!queryAddr.hasProvince() || !queryAddr.hasCity() || !queryAddr.hasCounty()){
			LOG.warn("[addr] [find-similar] [addr-err] "
					+ (queryAddr.hasProvince() ? queryAddr.getProvince().getName() : "X") + "-"
					+ (queryAddr.hasCity() ? queryAddr.getCity().getName() : "X") + "-"
					+ (queryAddr.hasCounty() ? queryAddr.getCounty().getName() : "X")
					+ " << " + addressText);
			throw new RuntimeException("Can't interpret address, invalid province, city or county name!");
		}
		
		//从文件缓存或内存缓存获取所有文档。
		List<Document> allDocs = loadDocunentsFromCache(queryAddr);
		if(allDocs.isEmpty()) {
			String message = queryAddr.getProvince().getName() + queryAddr.getCity().getName();
			if(!RegionType.CityLevelCounty.equals(queryAddr.getCounty().getType()))
				message = message + queryAddr.getCounty().getName();
			throw new NoHistoryDataException(message);
		}
		
		//为词条计算特征值
		Document queryDoc = analyse(queryAddr);
		query.setQueryAddr(queryAddr);
		query.setQueryDoc(queryDoc);
		
		//对应地址库中每条地址计算相似度，并保留相似度最高的topN条地址
		for(Document doc : allDocs){
			startCompute = System.currentTimeMillis();
			if(mode==1) computeDocSimilarity1(query, doc);
			else if(mode==2) computeDocSimilarity2(query, doc);
			elapsedCompute += System.currentTimeMillis() - startCompute;
		}
		
		//按相似度从高到低排序
		query.sortSimilarDocs();
		
		LOG.info("[addr] [find-similar] [perf] elapsed " + (System.currentTimeMillis() - start)
				+ "ms (com=" + elapsedCompute + "ms), " + addressText);
		
		return query;
	}
	
	/**
	 * 计算2个文档的相似度。
	 * <p>采用余弦相似度，0 &lt;= 返回值 &lt;= 1，值越大表示相似度越高，返回值为1则表示完全相同。</p>
	 * @param qryDoc
	 * @param doc
	 * @return
	 */
	public void computeDocSimilarity1(Query query, Document doc){
		double sumQD=0, sumQQ=0, sumDD=0, qtfidf=0, dtfidf=0;
		double qtf = 1; // / Math.log(qryDoc.getTerms().size());
		double dtf = 1; // / Math.log(doc.getTerms().size());
		
		Term dterm = null;
		//Text类型词条匹配情况
		int qryTextTermCount = 0; //查询文档Text类型词条数量
		int matchCount = 0, matchStart = -1, matchEnd = -1; //地址库文档匹配上的Text词条数量
		for(Term qterm : query.getQueryDoc().getTerms()){
			if(!TermType.Text.equals(qterm.getType())) continue; //仅针对Text类型词条计算 词条稠密度、词条匹配率
			qryTextTermCount++;
			for(int i=0; i< doc.getTerms().size(); i++){
				Term term = doc.getTerms().get(i);
				if(!TermType.Text.equals(term.getType())) continue; //仅针对Text类型词条计算 词条稠密度、词条匹配率
				if(term.getText().equals(qterm.getText())){
					matchCount++;
					if(matchStart==-1) {
						matchStart = matchEnd = i;
						break;
					}
					if(i>matchEnd) matchEnd = i;
					else if(i<matchStart) matchStart = i;
					break;
				}
			}
		}
		//计算词条稠密度、词条匹配率
		double termDensity = 1, termMatchRate = 1;
		if(qryTextTermCount>0) termMatchRate = Math.sqrt(matchCount * 1.0 / qryTextTermCount) * 0.5 + 0.5;
		//词条稠密度：
		// 查询文档a的文本词条为：【翠微西里】
		// 地址库文档词条为：【翠微北里12号翠微嘉园B座西801】
		// 地址库词条能匹配上【翠微西里】的每一个词条，但不是连续匹配，中间间隔了其他词条，稠密度不够，这类文档应当比能够连续匹配上查询文档的权重低
		//稠密度 = 0.7 + (匹配上查询文档的词条数量 / 匹配上的词条起止位置间总词条数量) * 0.3 
		//   乘以0.3是为了将稠密度对相似度结果的影响限制在 0 - 0.3 的范围内。
		//假设：查询文档中Text类型的词条为：翠, 微, 西, 里。地址库中有如下两个文档，Text类型的词条为：
		//1: 翠, 微, 西, 里, 10, 号, 楼
		//2: 翠, 微, 北, 里, 89, 号, 西, 2, 楼
		//则：
		// density1 = 0.7 + ( 4/4 ) * 0.3 = 0.7 + 0.3 = 1
		// density2 = 0.7 + ( 4/7 ) * 0.3 = 0.7 + 0.17143 = 0.87143
		// 文档2中 [翠、微、西、里] 4个词匹配上查询文档词条，这4个词条之间共包含7个词条。
		if(qryTextTermCount>=2 && matchCount>=2) 
			termDensity = Math.sqrt( matchCount * 1.0 / (matchEnd - matchStart + 1) ) * 0.5 + 0.5;
		
		SimilarDoc simiDoc = new SimilarDoc(doc);
		simiDoc.setTextPercent(1);
		
		//计算TF-IDF和相似度所需的中间值
		double dboost = 0, qboost = 0; //加权值
		for(Term qterm : query.getQueryDoc().getTerms()) {
			qboost = getBoostValue(false, query.getQueryDoc(), qterm, null, null);
			qtfidf = qtf * qterm.getIdf() * qboost;
			dterm = doc.getTerm(qterm.getText());
			if(dterm==null && TermType.RoadNum.equals(qterm.getType())){
				//从b中找门牌号词条
				for(Term t : doc.getTerms()){
					if(TermType.RoadNum.equals(t.getType())){
						if(t.getRef()!=null && t.getRef().equals(qterm.getRef())){ //道路相同
							dterm = t;
						}
						break;
					}
				}
			}
			dboost = dterm==null ? 0 : getBoostValue(true, query.getQueryDoc(), qterm, doc, dterm);
			double rate = (dterm!=null && TermType.Text.equals(dterm.getType())) ? termMatchRate : 1;
			double density = (dterm!=null && TermType.Text.equals(dterm.getType())) ? termDensity : 1;
			dtfidf = dtf * qterm.getIdf() * dboost * rate * density;
			
			MatchedTerm mt = null;
			if(dterm!=null){
				mt = new MatchedTerm(dterm);
				mt.setBoost(dboost);
				mt.setDensity(density);
				mt.setRate(rate);
				mt.setTfidf(dtfidf);
				simiDoc.addMatchedTerm(mt);
			}
			
			sumQQ += qtfidf * qtfidf;
			sumQD += qtfidf * dtfidf;
			sumDD += dtfidf * dtfidf;
		}
		if(sumDD==0 || sumQQ==0) return;
		
		simiDoc.setSimilarity(sumQD / ( Math.sqrt(sumQQ * sumDD) ));
		simiDoc.setTextValue(simiDoc.getSimilarity());
		query.addSimiDoc(simiDoc);
	}
	
	public void computeDocSimilarity2(Query query, Document doc){
		SimilarDoc simiDoc = new SimilarDoc(doc);
		
		//=====================================================================
		//文本匹配
		double sumQD=0, sumQQ=0, sumDD=0, qtfidf=0;
		
		Term term = null;
		//Text类型词条匹配情况
		int textTermNum = 0; //查询文档Text类型词条数量
		int matchCount = 0, matchStart = -1, matchEnd = -1; //地址库文档匹配上的Text词条数量
		for(Term qterm : query.getQueryDoc().getTerms()){
			if(!TermType.Text.equals(qterm.getType())) continue; //仅针对Text类型词条计算 词条稠密度、词条匹配率
			textTermNum++;
			for(int i=0; i< doc.getTerms().size(); i++){
				term = doc.getTerms().get(i);
				if(!TermType.Text.equals(term.getType())) continue; //仅针对Text类型词条计算 词条稠密度、词条匹配率
				if(term.getText().equals(qterm.getText())){
					matchCount++;
					if(matchStart==-1) {
						matchStart = matchEnd = i;
						break;
					}
					if(i>matchEnd) matchEnd = i;
					else if(i<matchStart) matchStart = i;
					break;
				}
			}
		}
		//计算词条稠密度、词条匹配率
		double termDensity = 1, termMatchRate = 1;
		if(textTermNum>0) termMatchRate = Math.sqrt(matchCount * 1.0 / textTermNum) * 0.5 + 0.5;
		//词条稠密度：
		// 查询文档a的文本词条为：【翠微西里】
		// 地址库文档词条为：【翠微北里12号翠微嘉园B座西801】
		// 地址库词条能匹配上【翠微西里】的每一个词条，但不是连续匹配，中间间隔了其他词条，稠密度不够，这类文档应当比能够连续匹配上查询文档的权重低
		//稠密度 = 0.7 + (匹配上查询文档的词条数量 / 匹配上的词条起止位置间总词条数量) * 0.3 
		//   乘以0.3是为了将稠密度对相似度结果的影响限制在 0 - 0.3 的范围内。
		//假设：查询文档中Text类型的词条为：翠, 微, 西, 里。地址库中有如下两个文档，Text类型的词条为：
		//1: 翠, 微, 西, 里, 10, 号, 楼
		//2: 翠, 微, 北, 里, 89, 号, 西, 2, 楼
		//则：
		// density1 = 0.7 + ( 4/4 ) * 0.3 = 0.7 + 0.3 = 1
		// density2 = 0.7 + ( 4/7 ) * 0.3 = 0.7 + 0.17143 = 0.87143
		// 文档2中 [翠、微、西、里] 4个词匹配上查询文档词条，这4个词条之间共包含7个词条。
		if(textTermNum>=2 && matchCount>=2) 
			termDensity = Math.sqrt( matchCount * 1.0 / (matchEnd - matchStart + 1) ) * 0.5 + 0.5;
		
		//计算TF-IDF和相似度所需的中间值
		double qboost = 0; //加权值
		for(Term qterm : query.getQueryDoc().getTerms()) {
			if(TermType.Text!=qterm.getType()) continue; //仅计算Text类型词条的相似度
			qboost = getBoostValue(false, query.getQueryDoc(), qterm, null, null);
			qtfidf = qterm.getIdf() * qboost;
			term = doc.getTerm(qterm.getText());
			MatchedTerm mt = null;
			if(term!=null){
				mt = new MatchedTerm(term);
				mt.setBoost(getBoostValue(true, query.getQueryDoc(), qterm, doc, term));
				mt.setRate((term!=null && TermType.Text.equals(term.getType())) ? termMatchRate : 1);
				mt.setDensity((term!=null && TermType.Text.equals(term.getType())) ? termDensity : 1);
				mt.setTfidf(term.getIdf() * mt.getBoost() * mt.getRate() * mt.getDensity());
				simiDoc.addMatchedTerm(mt);
			}
			
			sumQQ += qtfidf * qtfidf;
			sumQD += qtfidf * (mt==null ? 0: mt.getTfidf());
			sumDD += (mt==null ? 0: mt.getTfidf()) * (mt==null ? 0: mt.getTfidf());
		}
		
		if(sumDD>0 && sumQQ>0) simiDoc.setTextValue(sumQD / ( Math.sqrt(sumQQ * sumDD) ));
		
		//=====================================================================
		//确定性匹配
		
		//找出乡镇、村，道路、门牌号
		Term qroad=null, qroadnum=null, qtown=null, qvillage=null, droad=null, droadnum=null, dtown=null, dvillage=null;
		for(Term qterm : query.getQueryDoc().getTerms()){
			switch(qterm.getType()){
				case Road: qroad = qterm; break;
				case RoadNum: qroadnum = qterm; break;
				case Town: qtown = qterm; break;
				case Village: qvillage=qterm; break;
				default:
			}
		}
		for(Term dterm : doc.getTerms()) {
			switch(dterm.getType()){
				case Road: droad = dterm; break;
				case RoadNum: droadnum = dterm; break;
				case Town: dtown = dterm; break;
				case Village: dvillage=dterm; break;
				default:
			}
		}
		
		//乡镇村确定性匹配
		if(qtown!=null && qtown.equals(dtown)) { //乡镇相同
			if(qvillage!=null && qvillage.equals(dvillage)) { //镇相同村相同: 相似度 -> [0.98, 1]
				simiDoc.setExactPercent(0.98);
				simiDoc.setExactValue(1);
				simiDoc.setTextPercent(1-simiDoc.getExactPercent());
				simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
				simiDoc.addMatchedTerm(new MatchedTerm(dtown));
				simiDoc.addMatchedTerm(new MatchedTerm(dvillage));
				query.addSimiDoc(simiDoc);
				return;
			} else { //镇相同: 相似度 -> [0.96, 1]
				simiDoc.setExactPercent(0.98);
				simiDoc.setExactValue(0.96/0.98);
				simiDoc.setTextPercent(1-simiDoc.getExactPercent());
				simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
				simiDoc.addMatchedTerm(new MatchedTerm(dtown));
				query.addSimiDoc(simiDoc);
				return;
			}
		}
		if(qtown!=null && dtown!=null){ //都有乡镇但乡镇不同: 相似度 -> [0, 0.8]
			simiDoc.setExactPercent(0.2);
			simiDoc.setExactValue(0);
			simiDoc.setTextPercent(1-simiDoc.getExactPercent());
			simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
			query.addSimiDoc(simiDoc);
			return;
		}
		
		//道路门牌号确定性匹配
		if(qroad!=null && qroad.equals(droad)) { //道路相同
			if(qroadnum!=null && droadnum!=null){
				int qnum = translateRoadNum(qroadnum.getText());
				int dnum = translateRoadNum(droadnum.getText());
				simiDoc.addMatchedTerm(new MatchedTerm(droad));
				simiDoc.addMatchedTerm(new MatchedTerm(droadnum));
				if(qnum==dnum) { //道路相同门牌号相同: 相似度 -> [0.98, 1]
					simiDoc.setExactPercent(0.98);
					simiDoc.setExactValue(1);
					simiDoc.setTextPercent(1-simiDoc.getExactPercent());
					simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
					query.addSimiDoc(simiDoc);
					return;
				} else { //道路相同且都有门牌号但门牌号不同: 相似度 -> [0.56, 1]，根据门牌号间隔大小、文本匹配度高低调整权重
					if(simiDoc.getTextValue()>0.9){ //文本部分高度匹配，降低门牌号权重，突出文本权重: 相似度 -> [0.88, 1]
						simiDoc.setExactPercent(0.2);
					}else{ //文本部分匹配度一般，基本代表文本匹配不够靠谱，因此突出门牌号权重，降低文本权重: 相似度 -> [0.56, 0.97]
						simiDoc.setExactPercent(0.7);
					}
					simiDoc.setExactValue( 0.8 + (1 / ( Math.sqrt(Math.sqrt( Math.abs(qnum-dnum)+1 )) ) ) * 0.2 );
					simiDoc.setTextPercent(1-simiDoc.getExactPercent());
					simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
					query.addSimiDoc(simiDoc);
					return;
				}
			}else{ //道路相同，其中一个没有门牌号或两个都没有门牌号: 相似度 -> [0.56, 0.97]，根据文本匹配度高低调整权重
				simiDoc.addMatchedTerm(new MatchedTerm(droad));
				if(simiDoc.getTextValue()>0.9){ //文本部分高度匹配，加权: 相似度 -> [0.9, 0.97]
					simiDoc.setExactValue(0.9);
					simiDoc.setExactPercent(0.3);
				}else{ //文本部分匹配度一般，基本代表文本匹配不够靠谱，降权: 相似度 -> [0.56, 0.83]
					simiDoc.setExactValue(0.8);
					simiDoc.setExactPercent(0.7);
				}
				simiDoc.setTextPercent(1-simiDoc.getExactPercent());
				simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
				query.addSimiDoc(simiDoc);
				return;
			}
		}
		if(qroad!=null && droad!=null){ //都有道路但道路不同: 相似度 -> [0, 0.93]
			if(simiDoc.getTextValue()>0.9){ //文本部分高度匹配，为文本加权: 相似度 -> [0.837, 0.93]
				simiDoc.setExactPercent(0.07);
			}else{ //文本部分匹配度一般，基本代表文本匹配不够靠谱，为文本降权: 相似度 -> [0, 0.85]
				simiDoc.setExactPercent(0.15);
			}
			simiDoc.setExactValue(0);
			simiDoc.setTextPercent(1-simiDoc.getExactPercent());
			simiDoc.setSimilarity(simiDoc.getExactValue()*simiDoc.getExactPercent()+simiDoc.getTextPercent()*simiDoc.getTextValue());
			query.addSimiDoc(simiDoc);
			return;
		}
		
		simiDoc.setSimilarity(simiDoc.getExactPercent()*simiDoc.getExactValue()+simiDoc.getTextPercent()*simiDoc.getTextValue());
		query.addSimiDoc(simiDoc);
	}
	
	
	
	/**
	 * 从文件或内存缓存读取加载文档。
	 * @param address 
	 * @return
	 */
	public List<Document> loadDocunentsFromCache(AddressEntity address){
		String cacheKey = buildCacheKey(address);
		if(cacheKey==null) return null;
		
		List<Document> docs = null;
		if(!cacheVectorsInMemory){
			//从文件读取
			docs = loadDocumentsFromFileCache(cacheKey);
			return docs;
		} else {
			//从内存读取，如果未缓存到内存，则从文件加载到内存中
			docs = VECTORS_CACHE.get(cacheKey);
			if(docs==null){
				synchronized (VECTORS_CACHE) {
					docs = VECTORS_CACHE.get(cacheKey);
					if(docs==null){
						docs = loadDocumentsFromFileCache(cacheKey);
						if(docs==null) docs = new ArrayList<Document>(0);
						VECTORS_CACHE.put(cacheKey, docs);
					}
				}
			}
		}
		//为所有词条计算IDF并缓存
		Map<String, Double> idfs = IDF_CACHE.get(cacheKey);
		if(idfs==null){
			synchronized (IDF_CACHE) {
				idfs = IDF_CACHE.get(cacheKey);
				if(idfs==null){
					Map<String, Integer> termReferences = statInverseDocRefers(docs);
					idfs = new HashMap<String, Double>(termReferences.size());
					for(Map.Entry<String, Integer> entry : termReferences.entrySet()){
						double idf = Math.log( docs.size() * 1.0 / (entry.getValue() + 1) );
						if(idf<0) idf = 0;
						idfs.put(entry.getKey(), idf);
					}
					IDF_CACHE.put(cacheKey, idfs);
				}
			}
		}
		
		for(Document doc : docs){
			for(Term term : doc.getTerms())
				term.setIdf(idfs.get(term.getText()));
		}
		
		return docs;
	}
	
	public String buildCacheKey(AddressEntity address){
		if(address==null || !address.hasProvince() || !address.hasCity()) return null;
		StringBuilder sb = new StringBuilder();
		sb.append(address.getProvince().getId()).append('-').append(address.getCity().getId());
		if(address.getCity().getChildren()!=null)
			sb.append('-').append(address.getCounty().getId());
		return sb.toString();
	}
	
	private List<Document> loadDocumentsFromFileCache(String key){
		List<Document> docs = new ArrayList<Document>();
		
		String filePath = getCacheFolder() + "/" + key + ".vt";
		File file = new File(filePath);
		if(!file.exists()) return docs;
		try {
			FileInputStream fsr = new FileInputStream(file);
            InputStreamReader sr = new InputStreamReader(fsr, "utf8");
            BufferedReader br = new BufferedReader(sr);
            String line = null;
            while((line = br.readLine()) != null){
                Document doc = deserialize(line);
                if(doc==null) continue;
                Term road=null, roadNum=null;
                for(Term t : doc.getTerms()){
                	if(TermType.Road.equals(t.getType())){
                		road = t;
                		continue;
                	}
                	if(TermType.RoadNum.equals(t.getType())){
                		roadNum = t;
                		continue;
                	}
                }
                if(roadNum!=null) roadNum.setRef(road);
                docs.add(doc);
            }
            br.close();
            sr.close();
            fsr.close();
	    } catch (Exception ex) {
	        LOG.error("[doc-vec] [cache] [error] Error in reading file: " + filePath, ex);
	    }
		
		return docs;
	}
	
	public void buildDocumentFileCache(String key, List<AddressEntity> addresses){
		long start = System.currentTimeMillis();
		
		if(addresses==null || addresses.isEmpty()) return;
		List<Document> docs = analyse(addresses);
		if(docs==null || docs.isEmpty()) return;
		
		String filePath = getCacheFolder() + "/" + key + ".vt";
		File file = new File(filePath);
		try {
			if(file.exists()) file.delete();
			file.createNewFile();
		} catch (IOException ex) {
			LOG.error("[doc-vec] [cache] [error] Error in creating file: " + filePath, ex);
			throw new RuntimeException("Error in creating file: " + filePath, ex);
		}
		
		OutputStream outStream = null;
		BufferedOutputStream bufferedStream = null; 
		try {
			outStream = new FileOutputStream(file);
			bufferedStream = new BufferedOutputStream(outStream);
			for(Document doc : docs){
				bufferedStream.write((serialize(doc)).getBytes("utf8"));
				bufferedStream.write('\n');
			}
			bufferedStream.flush();
		} catch (Exception ex) {
			LOG.error("[doc-vec] [cache] [error] Error in writing file: " + filePath, ex);
			throw new RuntimeException("Error in writing file: " + filePath, ex);
		}finally{
			if(bufferedStream!=null) try { bufferedStream.close(); } catch (IOException e) {}
			if(outStream!=null) try { outStream.close(); } catch (IOException e) {}
		}
		LOG.info("[doc-vec] [cache] " + key + ".vt, " 
				+ docs.size() + " docs, elapsed " + (System.currentTimeMillis() - start)/1000.0 + "s.");
	}
	
	private Term addTerm(String text, TermType type, List<Term> terms, RegionEntity region){
		String termText = text;
		if(termText.length()>=4 && region!=null && region.orderedNameAndAlias()!=null && !region.orderedNameAndAlias().isEmpty()){
			termText = region.orderedNameAndAlias().get(region.orderedNameAndAlias().size()-1);
		}
		for(Term term : terms){
			if(term.getText().equals(termText)) return term;
		}
		Term newTerm = new Term(type, termText);
		terms.add(newTerm);
		return newTerm;
	}
	
	public void setCacheVectorsInMemory(boolean value){
		cacheVectorsInMemory = value;
	}
	public void setInterpreter(AddressInterpreter value){
		interpreter = value;
	}
	public void setCacheFolder(String value){
		cacheFolder = value;
	}
	public String getCacheFolder(){
		String path = cacheFolder;
		if(path==null || path.trim().isEmpty()) path = DEFAULT_CACHE_FOLDER;
		File file = new File(path);
		if(!file.exists()) file.mkdirs();
		return file.getAbsolutePath();
	}
}