package com.cyy.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.apache.log4j.Logger;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisShardInfo;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPipeline;
import redis.clients.jedis.ShardedJedisPool;
import redis.clients.util.Hashing;

/** This is a modify.*/

/**
 * @Author chenchen
 * @Date 2021/11/4 3:50 下午
 * @Description REDIS 集群连接单例连接池客户端
 **/
public class REST implements Serializable {

    private static final Logger LOG = Logger.getLogger(REST.class);

    private static volatile REST _singleton = null;

    private static final String DEFAULT_REDIS_NODE = "localhost";

    private static final String DEFAULT_REDIS_PORT = "6379";

    private transient ShardedJedisPool shardedJedisPool;

    private final transient HashMap<String, ShardedJedisPool> shardedJedisPoolMap;

    private String server;

    private REST() {
        shardedJedisPoolMap = new HashMap<>();
    }

    public static synchronized REST getInstance() {
        if (_singleton == null) {
            _singleton = new REST();
        }
        return _singleton;
    }

    /**
     * 使用默认参数初始化REST
     *
     * @return REST
     */
    public REST initialize() {
        return initialize(DEFAULT_REDIS_NODE, DEFAULT_REDIS_PORT);
    }

    /**
     * 初始化REST
     *
     * @param host REDIS的host
     * @param port REDIS的端口
     * @return REST
     */
    public REST initialize(String host, String port) {
        String serverUri = String.format("%s:%s", host, port);
        return initialize(serverUri);
    }

    /**
     * 初始化
     *
     * @param serverUri REDIS集群配置
     * @return REST
     */
    public synchronized REST initialize(String serverUri) {

        if (shardedJedisPool != null && serverUri.equals(server)) {
            return this;
        }

        if (shardedJedisPoolMap.containsKey(serverUri)) {
            shardedJedisPool = shardedJedisPoolMap.get(serverUri);
            server = serverUri;
            return this;
        }

        LOG.info(String.format("@@@@@@@@@@ 初始化jedis连接池 [%s] @@@@@@@@@@", serverUri));
        server = serverUri;
        ArrayList<JedisShardInfo> infoList = new ArrayList<>();
        //            jedisShardInfo.setPassword("");
        Arrays.stream(server.split(",")).map(node -> new JedisShardInfo(node.split(":")[0], Integer.parseInt(node.split(":")[1]), node.replaceAll(":", "_"))).forEach(infoList::add);
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(14);
        poolConfig.setMaxIdle(14);
        poolConfig.setMinIdle(1);
        poolConfig.setMaxWaitMillis(2000);
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestOnReturn(false);

        shardedJedisPool = new ShardedJedisPool(poolConfig, infoList, Hashing.CRC32_HASH, null, true);
        shardedJedisPoolMap.put(serverUri, shardedJedisPool);
        return this;
    }

    /**
     * 摧毁连接池
     */
    public void destroy() {
        if (shardedJedisPool != null) {
            LOG.info(String.format("@@@@@@@@@@ 销毁jedis 连接池 [%s] @@@@@@@@@@", server));
            shardedJedisPool.destroy();
        }
    }

    // ###########################################################################################################################################

    /**
     * 加入布隆过滤器,批量
     *
     * @param kvs
     */
    public void addBatch(Map<String, ?> kvs) {
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            ShardedJedisPipeline pipe = jedis.pipelined();
            kvs.forEach((key, value) -> {
                Arrays.stream(((String) value).split(":"))
                        .map(RCustomBloomFilter::getIndexs)
                        .flatMapToLong(Arrays::stream)
                        .forEachOrdered(index -> pipe.setbit(String.format("{hb_v:%s}_0", key), index, true));
                //设置60天的ttl，60s为测试
                pipe.expire(String.format("{hb_v:%s}_0", key), 24 * 60 * 60 * 60);
            });
            pipe.sync();
        } catch (Exception e) {
            LOG.warn("@@@@@@@@@@ addBatch 异常 @@@@@@@@@@");
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
    }

    /**
     * 判断是否加入布隆过滤器，批量写日志
     *
     * @param kvs
     */
    public void containsBatch(Map<String, ?> kvs) {

        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
//            ShardedJedisPipeline pipe = jedis.pipelined();
            kvs.forEach((key, values) -> {
                Map<String, Boolean> hm = new HashMap<>();
                //按固定格式切割查询的数值
                String[] list = ((String) values).split(":");
                for (String s : list) {
                    Boolean result = contains(s, key, "v");
                    hm.put(s, result);
                }
//                for (String value : list) {
//                    //计算-根据key获取bitmap下标
//                    long[] indexs = RCustomBloomFilter.getIndexs(value);
//                    Arrays.stream(indexs).forEach(index -> pipe.getbit(String.format("{hb_v:%s}_0", key), index));
//                }
//                List<Object> result = pipe.syncAndReturnAll();
//                //存储布隆过滤器结果，这个计算获取数据的方式有问题
//                for (int i = 0; i < result.size(); i += 10) {
//                    boolean flag = false;
//                    for (int j = 0; j < 10; j++) {
//                        if ((boolean) result.get(i + j)) {
//                            flag = true;
//                            break;
//                        }
//                    }
//                    hm.put(list[i / 10], flag);
//                }

                hm.forEach((k, v) -> {
                    if (v){
                        System.out.printf("@@@@@@@@@@ 当前记录 key:%s,item:%s 在布隆过滤器中可能存在 @@@@@@@@@@%n", key, k);
                        LOG.info(String.format("@@@@@@@@@@ 当前记录 key:%s,item:%s 在布隆过滤器中可能存在 @@@@@@@@@@", key, k));
                    }
                    else{
                        System.out.printf("@@@@@@@@@@ 当前记录 key:%s,item:%s 在布隆过滤器一定不存在 @@@@@@@@@@%n", key, k);
                        LOG.error(String.format("@@@@@@@@@@ 当前记录 key:%s,item:%s 在布隆过滤器一定不存在 @@@@@@@@@@", key, k));
                    }
                });
            });
        } catch (Exception e) {
            System.out.println("@@@@@@@@@@ containsBatch 异常 @@@@@@@@@@");
            LOG.warn("@@@@@@@@@@ containsBatch 异常 @@@@@@@@@@");
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
    }

    /**
     * 加入布隆过滤器
     *
     * @param value id值
     * @param type  数据类型,v/s
     */
    public void add(String value, String uid, String type) {
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            ShardedJedisPipeline pipe = jedis.pipelined();
            long[] indexs = RCustomBloomFilter.getIndexs(value);
            for (Long index : indexs) pipe.setbit(String.format("{hb_%s:%s}_0", type, uid), index, true);
            pipe.expire(String.format("{hb_%s:%s}_0", type, uid), 60 * 24 * 3600);
            pipe.sync();
        } catch (Exception e) {
            LOG.warn("@@@@@@@@@@ add 异常 @@@@@@@@@@");
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
    }

    /**
     * 判断是否一定不存在布隆过滤器，true 一定不存在，false 不是一定不存在,单个key
     *
     * @param value id值
     * @param type  数据类型，v/s
     * @return
     */
    public Boolean contains(String value, String uid, String type) {
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            ShardedJedisPipeline pipe = jedis.pipelined();
            long[] indexs = RCustomBloomFilter.getIndexs(value);
            Arrays.stream(indexs).forEach(index -> pipe.getbit(String.format("{hb_%s:%s}_0", type, uid), index));
            for (Object o : pipe.syncAndReturnAll())
                if (!(Boolean) o)
                    return false;
        } catch (Exception e) {
            LOG.warn("@@@@@@@@@@ contains 异常 @@@@@@@@@@");
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
        return true;
    }

    // ###########################################################################################################################################

    /**
     * @param keys 传入的redis keys集合
     * @return List<Object>
     */
    public Map<String, Object> batchQueryByKeys(List<String> keys) {
        Map<String, Object> resultList = new HashMap<>();
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            for (String key : keys) {
                String value = jedis.get(key);
                if (value != null)
                    resultList.put(key, value);
            }
        } catch (Exception e) {
            LOG.warn("@@@@@@@@@@ batchQueryByKeys 异常 @@@@@@@@@@");
            e.printStackTrace();
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
        return resultList;
    }

    /**
     * @param kvs 传入的redis keys-values集合
     */
    public void batchPutInPipelined(Map<String, ?> kvs) {
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            ShardedJedisPipeline pipe = jedis.pipelined();
            kvs.forEach((key, value) -> {
                pipe.set(key, String.valueOf(value));
                //设置10天TTL->改为30天
                pipe.expire(key, 60 * 60 * 24 * 30);
            });
            pipe.sync();
        } catch (Exception e) {
            LOG.error("@@@@@@@@@@ batchPutInPipelined 异常 @@@@@@@@@@");
            LOG.error(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
    }

    /**
     * 删除key的方式
     *
     * @param kvs
     */
    public void setDeleteKey(Set<String> kvs, int rebirth) {
        ShardedJedis jedis = null;
        try {
            jedis = shardedJedisPool.getResource();
            ShardedJedisPipeline pipe = jedis.pipelined();
            kvs.forEach(key -> pipe.expire(key, rebirth * 10));
            pipe.sync();
        } catch (Exception e) {
            LOG.warn("@@@@@@@@@@ setDeleteKey 异常 @@@@@@@@@@");
            LOG.warn(e);
        } finally {
            Objects.requireNonNull(jedis).close();
        }
    }

    /**
     * json 过滤器
     *
     * @param array
     * @param startTime
     * @return
     */
    public static JSONArray customSort(JSONArray array, Long startTime) {
        array.sort(Comparator.comparing(obj -> ((JSONObject) obj).getString("t")).reversed());
        array.removeIf(obj -> Long.parseLong(((JSONObject) obj).getString("t")) < startTime);
        return array;
    }

    public static void main(String[] args) {
        Map<String,String> hashMap = new HashMap<>();
        REST rest = REST.getInstance().initialize();
        hashMap.put("100000118","reader_3_379453:thread_1_91459757:thread_1_91404710:reader_3_379413:thread_1_91465713:thread_1_91119369:reader_3_375384:thread_1_91712612:adv_72_57489:thread_1_91629896:thread_1_91426566:thread_1_90612476:thread_1_68572318:thread_1_90863413:thread_1_49751351:thread_1_91926304:thread_1_90569832:thread_1_90414851:thread_1_90474743:thread_1_91561198:thread_1_91371876:thread_1_90780247:thread_1_91200134:thread_1_91979020:thread_1_91270058:thread_1_90610023:thread_1_91392131:thread_1_91522952:thread_1_91956986:thread_1_90723207:thread_1_68420360:thread_1_91150398:thread_1_90423218:thread_1_89721343:thread_1_91937463:ask_1_24067524:ask_1_24077215:reader_3_370966:reader_3_367804:ask_1_24076845:reader_3_382484:reader_3_367751:reader_3_366710:reader_3_374545:reader_3_379392:reader_3_371370:thread_1_90792183:thread_1_36555206:thread_1_91385679:thread_1_50152548:ask_1_24085556:thread_1_56396001:thread_1_91580199:thread_1_40802498:thread_1_68596492:thread_1_69180098:thread_1_45573679:thread_1_42374275:thread_1_41116020:thread_1_92036427:thread_1_68995022:thread_1_91941005:reader_3_381940:thread_1_67635991:thread_1_42088244:thread_1_90720877:thread_1_91941021:reader_3_375845:thread_1_90967572:thread_1_91488327:thread_1_90937088:reader_3_369096:ask_1_24080327:thread_1_54483938:reader_3_380489:thread_1_69196631:thread_1_91522046:thread_1_92206148:thread_1_91988614:thread_1_42886788:reader_3_372053:thread_1_91403278:thread_1_53268196:thread_1_90760112:thread_1_91033472:thread_1_92072604:thread_1_92104080:thread_1_91355075:thread_1_45077585:thread_1_91668902:reader_3_379687:thread_1_91075625:thread_1_91472239:thread_1_92034836:thread_1_57068339:thread_1_90643380:thread_1_90800416:thread_1_91529572:thread_1_91259131:thread_1_90951702:ask_1_24086931:thread_1_90762487:reader_3_383089:reader_3_375917:thread_1_68902442:thread_1_90925631:thread_1_91680251:adv_7_85385:thread_1_90967101:thread_1_90351205:thread_1_90718567:thread_1_91187685:thread_1_91005966:thread_1_91431666:thread_1_90978376:thread_1_91036448:ask_1_18076913:ask_1_23411923:thread_1_46587245:thread_1_92271518:thread_1_92102448:adv_0_72404:thread_1_50766252:reader_3_381554:class_1_16854:reader_3_371446:thread_1_90289671:reader_3_366764:adv_17_71690:reader_3_368587:thread_1_90892054:thread_1_90909715:thread_1_91269410:thread_1_67626316:thread_1_69542867:thread_1_91058318:reader_3_372680:thread_1_68222979:thread_1_69825243:thread_1_91212334:thread_1_90703371:thread_1_90795100:thread_1_90654412:thread_1_91341594:thread_1_91674383:thread_1_69416659:thread_1_90422584:thread_1_90459729:reader_3_369101:thread_1_91425069:thread_1_90874019:thread_1_91465838:reader_3_375205:thread_1_91298807:thread_1_91608857:thread_1_91738760:thread_1_91170394:ask_1_24074634:thread_1_69489129:thread_1_90682011:ask_1_24080264:thread_1_91500528:ask_1_24074451:thread_1_91926432:thread_1_91713660:thread_1_67638464:ask_1_24072393:thread_1_90654697:thread_1_91953881:thread_1_90892056:thread_1_91383842:thread_1_68608523:thread_1_90975963:thread_1_91489770:thread_1_91347418:thread_1_90632869:thread_1_91475335:thread_1_91632416:thread_1_91212583:thread_1_91537080:thread_1_91452898:thread_1_90660378:thread_1_91931198:reader_3_369697:thread_1_90743434:thread_1_91212444:thread_1_54836361:thread_1_91328804:reader_3_365118:thread_1_91016851:thread_1_80920786:thread_1_91350898:thread_1_69837213:thread_1_68739293:thread_1_91430726:thread_1_90685133:thread_1_91456694:thread_1_91264025:thread_1_90758955:thread_1_91140166:thread_1_91425137:thread_1_91305009:thread_1_91480189:thread_1_89954224:class_1_9383:thread_1_57174532:thread_1_91290114:thread_1_91242618:thread_1_90888058:thread_1_86055381:thread_1_90384014:thread_1_69888401:thread_1_90723261:reader_3_367962:thread_1_91637855:thread_1_91376198:thread_1_91465995:thread_1_68547035:thread_1_68771046:thread_1_91573632:thread_1_90931039:thread_1_91230206:reader_3_369749:thread_1_68713009:thread_1_69653077:thread_1_91747318:thread_1_92325531:reader_3_381305:reader_3_374148:reader_3_373830:ask_1_24088434:reader_3_376199:reader_3_402539:ask_1_17698922:ask_1_19310036:ask_1_16945883:reader_3_391124:reader_3_382793:reader_3_384233:reader_3_384517:ask_1_24080453:thread_1_91697475:reader_3_374024:thread_1_69402892:thread_1_91657240:thread_1_91495288:thread_1_91658468:thread_1_91561673:thread_1_91016850:thread_1_91433239:thread_1_49609924:thread_1_91177923:thread_1_91342637:thread_1_91673953:thread_1_91502338:thread_1_91092698:thread_1_68014939:thread_1_90568298:thread_1_69595900:thread_1_91046932:thread_1_90684509:thread_1_90876300:reader_3_377223:class_1_10695:reader_3_379701:thread_1_92167910:thread_1_92163599:thread_1_91698199:thread_1_44324331:thread_1_68887293:thread_1_69486016:thread_1_92010705:thread_1_91947697:thread_1_90805361:thread_1_91118659:thread_1_91618606:thread_1_92130353:reader_3_382471:thread_1_91709586:thread_1_91709381:thread_1_91509685:thread_1_69027980:thread_1_92153476:thread_1_69294127:thread_1_92137717:thread_1_69355300:thread_1_68253055:thread_1_69790684:reader_3_382020:thread_1_89947671:thread_1_92023609:thread_1_69502190:thread_1_91465391:thread_1_91014226:thread_1_69736414:thread_1_68870971:thread_1_69591170:reader_3_376205:thread_1_91286488:thread_1_90909632:thread_1_91580633:thread_1_91196975:thread_1_90940286:thread_1_90931499:thread_1_67509272:thread_1_90691026:thread_1_90636672:thread_1_92245131:ask_1_24088700:thread_1_68367590:thread_1_91974108:thread_1_92353555:reader_3_375324:ask_1_19704085:thread_1_91210638:thread_1_92288068:thread_1_92059636:thread_1_91148297:thread_1_92422713:thread_1_91638340:thread_1_92432466:thread_1_91164491:thread_1_92314422:thread_1_68677289:reader_3_382906:thread_1_91413939:thread_1_92547875:reader_3_403083:thread_1_90703366:reader_3_383446:thread_1_69461682:thread_1_91348380:thread_1_91532003:thread_1_91585996:thread_1_91935959:thread_1_91302010:thread_1_91572156:thread_1_92110560:thread_1_92365533:thread_1_91741421:thread_1_90761028:thread_1_92142761:thread_1_68015347:thread_1_68380484:thread_1_50619776:thread_1_90788834:thread_1_75949438:thread_1_37680413:thread_1_48839960:thread_1_47089356:ask_1_24088083:thread_1_47824879:thread_1_49189074:thread_1_47848307:thread_1_68712080:thread_1_49999191:thread_1_92142906:thread_1_49821967:thread_1_89049547:thread_1_47979566:thread_1_48578099:thread_1_69009433:thread_1_68831774:thread_1_68424366:thread_1_69668205:thread_1_92333657:thread_1_50076656:thread_1_92135954:thread_1_68986866:thread_1_47808736:thread_1_92143275:thread_1_69640902:thread_1_77246982:thread_1_68746516:thread_1_92485171:thread_1_91399057:thread_1_92463970:thread_1_92177257:thread_1_92422712:ask_1_19559598:thread_1_91428677:thread_1_90889957:thread_1_90566271:thread_1_90564931:thread_1_91405462:thread_1_89948535:thread_1_90362377:thread_1_68175445:thread_1_67644425:thread_1_90854302:thread_1_90355926:thread_1_69189638:thread_1_89704154:thread_1_69828799:thread_1_91199093:thread_1_91605184:thread_1_89667080:thread_1_91242764:thread_1_91431463:thread_1_91554202:thread_1_90520046:thread_1_91466607:thread_1_90570425:thread_1_91510374:reader_3_372534:thread_1_69028835:thread_1_91440105:thread_1_68705890:thread_1_69458304:thread_1_69288313:thread_1_68768886:thread_1_91208478:thread_1_69636944:thread_1_90412694:reader_3_361468:thread_1_89025849:thread_1_68701660:thread_1_68417953:thread_1_68223009:thread_1_90903514:thread_1_68506218:thread_1_90967399:thread_1_91232374:thread_1_69671083:thread_1_91047991:thread_1_90403515:thread_1_91286058:thread_1_90875944:thread_1_68215611:thread_1_69429485:thread_1_68240746:thread_1_90908007:thread_1_48964832:reader_3_380813:thread_1_92514493:thread_1_69829892:reader_3_383884:thread_1_92285856:ask_1_17103446:ask_1_24098311:thread_1_51984635:ask_1_18161309:reader_3_385683:thread_1_92622888:reader_3_388369:reader_3_385539:reader_3_383800:reader_3_377748:reader_3_376465:reader_3_377693:mamagoods_1_3046:adv_7_82280:thread_1_67449672:reader_3_375016:thread_1_91398013:reader_3_368345:ask_1_24082671:thread_1_90950274:thread_1_68067330:thread_1_91971052:thread_1_91466604:thread_1_91498042:thread_1_43571013:thread_1_43782031:thread_1_90365896:thread_1_69610811:thread_1_41897391:thread_1_90436967:thread_1_43418317:thread_1_92001255:thread_1_69283566:thread_1_38498181:thread_1_69470117:thread_1_69418787:thread_1_91204177:thread_1_91256281:thread_1_91010282:thread_1_91301036:thread_1_91371799:thread_1_91378351:thread_1_91180223:thread_1_90951606:thread_1_41791434:thread_1_91572168:thread_1_90922251:ask_1_20882215:reader_3_383109:ask_2_18940559:thread_1_68338263:thread_1_91206058:ask_1_24083546:thread_1_90838305:thread_1_91168195:thread_1_91317515:thread_1_90939778:thread_1_90804493:thread_1_90921168:thread_1_90943261:thread_1_90973267:thread_1_90996364:thread_1_90616332:reader_3_373774:thread_1_90709276:thread_1_89951765:reader_3_374498:ask_1_24068795:thread_1_91080412:ask_1_24080298:thread_1_37038398:thread_1_69035140:thread_1_90651066:thread_1_69679417:thread_1_68599141:thread_1_68481196:thread_1_91420434:thread_1_69360155:thread_1_89649498:thread_1_68688196:thread_1_91446108:thread_1_68671888:thread_1_68194692:thread_1_67597227:thread_1_90845214:thread_1_67585311:thread_1_91659557:reader_3_378758:thread_1_69837308");
        hashMap.put("100000119","reader_3_379453:thread_1_91459757:thread_1_91404710:reader_3_379413:thread_1_91465713:thread_1_91119369:reader_3_375384:thread_1_91712612:adv_72_57489:thread_1_91629896:thread_1_91426566:thread_1_90612476:thread_1_68572318:thread_1_90863413:thread_1_49751351:thread_1_91926304:thread_1_90569832:thread_1_90414851:thread_1_90474743:thread_1_91561198:thread_1_91371876:thread_1_90780247:thread_1_91200134:thread_1_91979020:thread_1_91270058:thread_1_90610023:thread_1_91392131:thread_1_91522952:thread_1_91956986:thread_1_90723207:thread_1_68420360:thread_1_91150398:thread_1_90423218:thread_1_89721343:thread_1_91937463:ask_1_24067524:ask_1_24077215:reader_3_370966:reader_3_367804:ask_1_24076845:reader_3_382484:reader_3_367751:reader_3_366710:reader_3_374545:reader_3_379392:reader_3_371370:thread_1_90792183:thread_1_36555206:thread_1_91385679:thread_1_50152548:ask_1_24085556:thread_1_56396001:thread_1_91580199:thread_1_40802498:thread_1_68596492:thread_1_69180098:thread_1_45573679:thread_1_42374275:thread_1_41116020:thread_1_92036427:thread_1_68995022:thread_1_91941005:reader_3_381940:thread_1_67635991:thread_1_42088244:thread_1_90720877:thread_1_91941021:reader_3_375845:thread_1_90967572:thread_1_91488327:thread_1_90937088:reader_3_369096:ask_1_24080327:thread_1_54483938:reader_3_380489:thread_1_69196631:thread_1_91522046:thread_1_92206148:thread_1_91988614:thread_1_42886788:reader_3_372053:thread_1_91403278:thread_1_53268196:thread_1_90760112:thread_1_91033472:thread_1_92072604:thread_1_92104080:thread_1_91355075:thread_1_45077585:thread_1_91668902:reader_3_379687:thread_1_91075625:thread_1_91472239:thread_1_92034836:thread_1_57068339:thread_1_90643380:thread_1_90800416:thread_1_91529572:thread_1_91259131:thread_1_90951702:ask_1_24086931:thread_1_90762487:reader_3_383089:reader_3_375917:thread_1_68902442:thread_1_90925631:thread_1_91680251:adv_7_85385:thread_1_90967101:thread_1_90351205:thread_1_90718567:thread_1_91187685:thread_1_91005966:thread_1_91431666:thread_1_90978376:thread_1_91036448:ask_1_18076913:ask_1_23411923:thread_1_46587245:thread_1_92271518:thread_1_92102448:adv_0_72404:thread_1_50766252:reader_3_381554:class_1_16854:reader_3_371446:thread_1_90289671:reader_3_366764:adv_17_71690:reader_3_368587:thread_1_90892054:thread_1_90909715:thread_1_91269410:thread_1_67626316:thread_1_69542867:thread_1_91058318:reader_3_372680:thread_1_68222979:thread_1_69825243:thread_1_91212334:thread_1_90703371:thread_1_90795100:thread_1_90654412:thread_1_91341594:thread_1_91674383:thread_1_69416659:thread_1_90422584:thread_1_90459729:reader_3_369101:thread_1_91425069:thread_1_90874019:thread_1_91465838:reader_3_375205:thread_1_91298807:thread_1_91608857:thread_1_91738760:thread_1_91170394:ask_1_24074634:thread_1_69489129:thread_1_90682011:ask_1_24080264:thread_1_91500528:ask_1_24074451:thread_1_91926432:thread_1_91713660:thread_1_67638464:ask_1_24072393:thread_1_90654697:thread_1_91953881:thread_1_90892056:thread_1_91383842:thread_1_68608523:thread_1_90975963:thread_1_91489770:thread_1_91347418:thread_1_90632869:thread_1_91475335:thread_1_91632416:thread_1_91212583:thread_1_91537080:thread_1_91452898:thread_1_90660378:thread_1_91931198:reader_3_369697:thread_1_90743434:thread_1_91212444:thread_1_54836361:thread_1_91328804:reader_3_365118:thread_1_91016851:thread_1_80920786:thread_1_91350898:thread_1_69837213:thread_1_68739293:thread_1_91430726:thread_1_90685133:thread_1_91456694:thread_1_91264025:thread_1_90758955:thread_1_91140166:thread_1_91425137:thread_1_91305009:thread_1_91480189:thread_1_89954224:class_1_9383:thread_1_57174532:thread_1_91290114:thread_1_91242618:thread_1_90888058:thread_1_86055381:thread_1_90384014:thread_1_69888401:thread_1_90723261:reader_3_367962:thread_1_91637855:thread_1_91376198:thread_1_91465995:thread_1_68547035:thread_1_68771046:thread_1_91573632:thread_1_90931039:thread_1_91230206:reader_3_369749:thread_1_68713009:thread_1_69653077:thread_1_91747318:thread_1_92325531:reader_3_381305:reader_3_374148:reader_3_373830:ask_1_24088434:reader_3_376199:reader_3_402539:ask_1_17698922:ask_1_19310036:ask_1_16945883:reader_3_391124:reader_3_382793:reader_3_384233:reader_3_384517:ask_1_24080453:thread_1_91697475:reader_3_374024:thread_1_69402892:thread_1_91657240:thread_1_91495288:thread_1_91658468:thread_1_91561673:thread_1_91016850:thread_1_91433239:thread_1_49609924:thread_1_91177923:thread_1_91342637:thread_1_91673953:thread_1_91502338:thread_1_91092698:thread_1_68014939:thread_1_90568298:thread_1_69595900:thread_1_91046932:thread_1_90684509:thread_1_90876300:reader_3_377223:class_1_10695:reader_3_379701:thread_1_92167910:thread_1_92163599:thread_1_91698199:thread_1_44324331:thread_1_68887293:thread_1_69486016:thread_1_92010705:thread_1_91947697:thread_1_90805361:thread_1_91118659:thread_1_91618606:thread_1_92130353:reader_3_382471:thread_1_91709586:thread_1_91709381:thread_1_91509685:thread_1_69027980:thread_1_92153476:thread_1_69294127:thread_1_92137717:thread_1_69355300:thread_1_68253055:thread_1_69790684:reader_3_382020:thread_1_89947671:thread_1_92023609:thread_1_69502190:thread_1_91465391:thread_1_91014226:thread_1_69736414:thread_1_68870971:thread_1_69591170:reader_3_376205:thread_1_91286488:thread_1_90909632:thread_1_91580633:thread_1_91196975:thread_1_90940286:thread_1_90931499:thread_1_67509272:thread_1_90691026:thread_1_90636672:thread_1_92245131:ask_1_24088700:thread_1_68367590:thread_1_91974108:thread_1_92353555:reader_3_375324:ask_1_19704085:thread_1_91210638:thread_1_92288068:thread_1_92059636:thread_1_91148297:thread_1_92422713:thread_1_91638340:thread_1_92432466:thread_1_91164491:thread_1_92314422:thread_1_68677289:reader_3_382906:thread_1_91413939:thread_1_92547875:reader_3_403083:thread_1_90703366:reader_3_383446:thread_1_69461682:thread_1_91348380:thread_1_91532003:thread_1_91585996:thread_1_91935959:thread_1_91302010:thread_1_91572156:thread_1_92110560:thread_1_92365533:thread_1_91741421:thread_1_90761028:thread_1_92142761:thread_1_68015347:thread_1_68380484:thread_1_50619776:thread_1_90788834:thread_1_75949438:thread_1_37680413:thread_1_48839960:thread_1_47089356:ask_1_24088083:thread_1_47824879:thread_1_49189074:thread_1_47848307:thread_1_68712080:thread_1_49999191:thread_1_92142906:thread_1_49821967:thread_1_89049547:thread_1_47979566:thread_1_48578099:thread_1_69009433:thread_1_68831774:thread_1_68424366:thread_1_69668205:thread_1_92333657:thread_1_50076656:thread_1_92135954:thread_1_68986866:thread_1_47808736:thread_1_92143275:thread_1_69640902:thread_1_77246982:thread_1_68746516:thread_1_92485171:thread_1_91399057:thread_1_92463970:thread_1_92177257:thread_1_92422712:ask_1_19559598:thread_1_91428677:thread_1_90889957:thread_1_90566271:thread_1_90564931:thread_1_91405462:thread_1_89948535:thread_1_90362377:thread_1_68175445:thread_1_67644425:thread_1_90854302:thread_1_90355926:thread_1_69189638:thread_1_89704154:thread_1_69828799:thread_1_91199093:thread_1_91605184:thread_1_89667080:thread_1_91242764:thread_1_91431463:thread_1_91554202:thread_1_90520046:thread_1_91466607:thread_1_90570425:thread_1_91510374:reader_3_372534:thread_1_69028835:thread_1_91440105:thread_1_68705890:thread_1_69458304:thread_1_69288313:thread_1_68768886:thread_1_91208478:thread_1_69636944:thread_1_90412694:reader_3_361468:thread_1_89025849:thread_1_68701660:thread_1_68417953:thread_1_68223009:thread_1_90903514:thread_1_68506218:thread_1_90967399:thread_1_91232374:thread_1_69671083:thread_1_91047991:thread_1_90403515:thread_1_91286058:thread_1_90875944:thread_1_68215611:thread_1_69429485:thread_1_68240746:thread_1_90908007:thread_1_48964832:reader_3_380813:thread_1_92514493:thread_1_69829892:reader_3_383884:thread_1_92285856:ask_1_17103446:ask_1_24098311:thread_1_51984635:ask_1_18161309:reader_3_385683:thread_1_92622888:reader_3_388369:reader_3_385539:reader_3_383800:reader_3_377748:reader_3_376465:reader_3_377693:mamagoods_1_3046:adv_7_82280:thread_1_67449672:reader_3_375016:thread_1_91398013:reader_3_368345:ask_1_24082671:thread_1_90950274:thread_1_68067330:thread_1_91971052:thread_1_91466604:thread_1_91498042:thread_1_43571013:thread_1_43782031:thread_1_90365896:thread_1_69610811:thread_1_41897391:thread_1_90436967:thread_1_43418317:thread_1_92001255:thread_1_69283566:thread_1_38498181:thread_1_69470117:thread_1_69418787:thread_1_91204177:thread_1_91256281:thread_1_91010282:thread_1_91301036:thread_1_91371799:thread_1_91378351:thread_1_91180223:thread_1_90951606:thread_1_41791434:thread_1_91572168:thread_1_90922251:ask_1_20882215:reader_3_383109:ask_2_18940559:thread_1_68338263:thread_1_91206058:ask_1_24083546:thread_1_90838305:thread_1_91168195:thread_1_91317515:thread_1_90939778:thread_1_90804493:thread_1_90921168:thread_1_90943261:thread_1_90973267:thread_1_90996364:thread_1_90616332:reader_3_373774:thread_1_90709276:thread_1_89951765:reader_3_374498:ask_1_24068795:thread_1_91080412:ask_1_24080298:thread_1_37038398:thread_1_69035140:thread_1_90651066:thread_1_69679417:thread_1_68599141:thread_1_68481196:thread_1_91420434:thread_1_69360155:thread_1_89649498:thread_1_68688196:thread_1_91446108:thread_1_68671888:thread_1_68194692:thread_1_67597227:thread_1_90845214:thread_1_67585311:thread_1_91659557:reader_3_378758:thread_1_69837308");
        hashMap.put("100000120","reader_3_379453:thread_1_91459757:thread_1_91404710:reader_3_379413:thread_1_91465713:thread_1_91119369:reader_3_375384:thread_1_91712612:adv_72_57489:thread_1_91629896:thread_1_91426566:thread_1_90612476:thread_1_68572318:thread_1_90863413:thread_1_49751351:thread_1_91926304:thread_1_90569832:thread_1_90414851:thread_1_90474743:thread_1_91561198:thread_1_91371876:thread_1_90780247:thread_1_91200134:thread_1_91979020:thread_1_91270058:thread_1_90610023:thread_1_91392131:thread_1_91522952:thread_1_91956986:thread_1_90723207:thread_1_68420360:thread_1_91150398:thread_1_90423218:thread_1_89721343:thread_1_91937463:ask_1_24067524:ask_1_24077215:reader_3_370966:reader_3_367804:ask_1_24076845:reader_3_382484:reader_3_367751:reader_3_366710:reader_3_374545:reader_3_379392:reader_3_371370:thread_1_90792183:thread_1_36555206:thread_1_91385679:thread_1_50152548:ask_1_24085556:thread_1_56396001:thread_1_91580199:thread_1_40802498:thread_1_68596492:thread_1_69180098:thread_1_45573679:thread_1_42374275:thread_1_41116020:thread_1_92036427:thread_1_68995022:thread_1_91941005:reader_3_381940:thread_1_67635991:thread_1_42088244:thread_1_90720877:thread_1_91941021:reader_3_375845:thread_1_90967572:thread_1_91488327:thread_1_90937088:reader_3_369096:ask_1_24080327:thread_1_54483938:reader_3_380489:thread_1_69196631:thread_1_91522046:thread_1_92206148:thread_1_91988614:thread_1_42886788:reader_3_372053:thread_1_91403278:thread_1_53268196:thread_1_90760112:thread_1_91033472:thread_1_92072604:thread_1_92104080:thread_1_91355075:thread_1_45077585:thread_1_91668902:reader_3_379687:thread_1_91075625:thread_1_91472239:thread_1_92034836:thread_1_57068339:thread_1_90643380:thread_1_90800416:thread_1_91529572:thread_1_91259131:thread_1_90951702:ask_1_24086931:thread_1_90762487:reader_3_383089:reader_3_375917:thread_1_68902442:thread_1_90925631:thread_1_91680251:adv_7_85385:thread_1_90967101:thread_1_90351205:thread_1_90718567:thread_1_91187685:thread_1_91005966:thread_1_91431666:thread_1_90978376:thread_1_91036448:ask_1_18076913:ask_1_23411923:thread_1_46587245:thread_1_92271518:thread_1_92102448:adv_0_72404:thread_1_50766252:reader_3_381554:class_1_16854:reader_3_371446:thread_1_90289671:reader_3_366764:adv_17_71690:reader_3_368587:thread_1_90892054:thread_1_90909715:thread_1_91269410:thread_1_67626316:thread_1_69542867:thread_1_91058318:reader_3_372680:thread_1_68222979:thread_1_69825243:thread_1_91212334:thread_1_90703371:thread_1_90795100:thread_1_90654412:thread_1_91341594:thread_1_91674383:thread_1_69416659:thread_1_90422584:thread_1_90459729:reader_3_369101:thread_1_91425069:thread_1_90874019:thread_1_91465838:reader_3_375205:thread_1_91298807:thread_1_91608857:thread_1_91738760:thread_1_91170394:ask_1_24074634:thread_1_69489129:thread_1_90682011:ask_1_24080264:thread_1_91500528:ask_1_24074451:thread_1_91926432:thread_1_91713660:thread_1_67638464:ask_1_24072393:thread_1_90654697:thread_1_91953881:thread_1_90892056:thread_1_91383842:thread_1_68608523:thread_1_90975963:thread_1_91489770:thread_1_91347418:thread_1_90632869:thread_1_91475335:thread_1_91632416:thread_1_91212583:thread_1_91537080:thread_1_91452898:thread_1_90660378:thread_1_91931198:reader_3_369697:thread_1_90743434:thread_1_91212444:thread_1_54836361:thread_1_91328804:reader_3_365118:thread_1_91016851:thread_1_80920786:thread_1_91350898:thread_1_69837213:thread_1_68739293:thread_1_91430726:thread_1_90685133:thread_1_91456694:thread_1_91264025:thread_1_90758955:thread_1_91140166:thread_1_91425137:thread_1_91305009:thread_1_91480189:thread_1_89954224:class_1_9383:thread_1_57174532:thread_1_91290114:thread_1_91242618:thread_1_90888058:thread_1_86055381:thread_1_90384014:thread_1_69888401:thread_1_90723261:reader_3_367962:thread_1_91637855:thread_1_91376198:thread_1_91465995:thread_1_68547035:thread_1_68771046:thread_1_91573632:thread_1_90931039:thread_1_91230206:reader_3_369749:thread_1_68713009:thread_1_69653077:thread_1_91747318:thread_1_92325531:reader_3_381305:reader_3_374148:reader_3_373830:ask_1_24088434:reader_3_376199:reader_3_402539:ask_1_17698922:ask_1_19310036:ask_1_16945883:reader_3_391124:reader_3_382793:reader_3_384233:reader_3_384517:ask_1_24080453:thread_1_91697475:reader_3_374024:thread_1_69402892:thread_1_91657240:thread_1_91495288:thread_1_91658468:thread_1_91561673:thread_1_91016850:thread_1_91433239:thread_1_49609924:thread_1_91177923:thread_1_91342637:thread_1_91673953:thread_1_91502338:thread_1_91092698:thread_1_68014939:thread_1_90568298:thread_1_69595900:thread_1_91046932:thread_1_90684509:thread_1_90876300:reader_3_377223:class_1_10695:reader_3_379701:thread_1_92167910:thread_1_92163599:thread_1_91698199:thread_1_44324331:thread_1_68887293:thread_1_69486016:thread_1_92010705:thread_1_91947697:thread_1_90805361:thread_1_91118659:thread_1_91618606:thread_1_92130353:reader_3_382471:thread_1_91709586:thread_1_91709381:thread_1_91509685:thread_1_69027980:thread_1_92153476:thread_1_69294127:thread_1_92137717:thread_1_69355300:thread_1_68253055:thread_1_69790684:reader_3_382020:thread_1_89947671:thread_1_92023609:thread_1_69502190:thread_1_91465391:thread_1_91014226:thread_1_69736414:thread_1_68870971:thread_1_69591170:reader_3_376205:thread_1_91286488:thread_1_90909632:thread_1_91580633:thread_1_91196975:thread_1_90940286:thread_1_90931499:thread_1_67509272:thread_1_90691026:thread_1_90636672:thread_1_92245131:ask_1_24088700:thread_1_68367590:thread_1_91974108:thread_1_92353555:reader_3_375324:ask_1_19704085:thread_1_91210638:thread_1_92288068:thread_1_92059636:thread_1_91148297:thread_1_92422713:thread_1_91638340:thread_1_92432466:thread_1_91164491:thread_1_92314422:thread_1_68677289:reader_3_382906:thread_1_91413939:thread_1_92547875:reader_3_403083:thread_1_90703366:reader_3_383446:thread_1_69461682:thread_1_91348380:thread_1_91532003:thread_1_91585996:thread_1_91935959:thread_1_91302010:thread_1_91572156:thread_1_92110560:thread_1_92365533:thread_1_91741421:thread_1_90761028:thread_1_92142761:thread_1_68015347:thread_1_68380484:thread_1_50619776:thread_1_90788834:thread_1_75949438:thread_1_37680413:thread_1_48839960:thread_1_47089356:ask_1_24088083:thread_1_47824879:thread_1_49189074:thread_1_47848307:thread_1_68712080:thread_1_49999191:thread_1_92142906:thread_1_49821967:thread_1_89049547:thread_1_47979566:thread_1_48578099:thread_1_69009433:thread_1_68831774:thread_1_68424366:thread_1_69668205:thread_1_92333657:thread_1_50076656:thread_1_92135954:thread_1_68986866:thread_1_47808736:thread_1_92143275:thread_1_69640902:thread_1_77246982:thread_1_68746516:thread_1_92485171:thread_1_91399057:thread_1_92463970:thread_1_92177257:thread_1_92422712:ask_1_19559598:thread_1_91428677:thread_1_90889957:thread_1_90566271:thread_1_90564931:thread_1_91405462:thread_1_89948535:thread_1_90362377:thread_1_68175445:thread_1_67644425:thread_1_90854302:thread_1_90355926:thread_1_69189638:thread_1_89704154:thread_1_69828799:thread_1_91199093:thread_1_91605184:thread_1_89667080:thread_1_91242764:thread_1_91431463:thread_1_91554202:thread_1_90520046:thread_1_91466607:thread_1_90570425:thread_1_91510374:reader_3_372534:thread_1_69028835:thread_1_91440105:thread_1_68705890:thread_1_69458304:thread_1_69288313:thread_1_68768886:thread_1_91208478:thread_1_69636944:thread_1_90412694:reader_3_361468:thread_1_89025849:thread_1_68701660:thread_1_68417953:thread_1_68223009:thread_1_90903514:thread_1_68506218:thread_1_90967399:thread_1_91232374:thread_1_69671083:thread_1_91047991:thread_1_90403515:thread_1_91286058:thread_1_90875944:thread_1_68215611:thread_1_69429485:thread_1_68240746:thread_1_90908007:thread_1_48964832:reader_3_380813:thread_1_92514493:thread_1_69829892:reader_3_383884:thread_1_92285856:ask_1_17103446:ask_1_24098311:thread_1_51984635:ask_1_18161309:reader_3_385683:thread_1_92622888:reader_3_388369:reader_3_385539:reader_3_383800:reader_3_377748:reader_3_376465:reader_3_377693:mamagoods_1_3046:adv_7_82280:thread_1_67449672:reader_3_375016:thread_1_91398013:reader_3_368345:ask_1_24082671:thread_1_90950274:thread_1_68067330:thread_1_91971052:thread_1_91466604:thread_1_91498042:thread_1_43571013:thread_1_43782031:thread_1_90365896:thread_1_69610811:thread_1_41897391:thread_1_90436967:thread_1_43418317:thread_1_92001255:thread_1_69283566:thread_1_38498181:thread_1_69470117:thread_1_69418787:thread_1_91204177:thread_1_91256281:thread_1_91010282:thread_1_91301036:thread_1_91371799:thread_1_91378351:thread_1_91180223:thread_1_90951606:thread_1_41791434:thread_1_91572168:thread_1_90922251:ask_1_20882215:reader_3_383109:ask_2_18940559:thread_1_68338263:thread_1_91206058:ask_1_24083546:thread_1_90838305:thread_1_91168195:thread_1_91317515:thread_1_90939778:thread_1_90804493:thread_1_90921168:thread_1_90943261:thread_1_90973267:thread_1_90996364:thread_1_90616332:reader_3_373774:thread_1_90709276:thread_1_89951765:reader_3_374498:ask_1_24068795:thread_1_91080412:ask_1_24080298:thread_1_37038398:thread_1_69035140:thread_1_90651066:thread_1_69679417:thread_1_68599141:thread_1_68481196:thread_1_91420434:thread_1_69360155:thread_1_89649498:thread_1_68688196:thread_1_91446108:thread_1_68671888:thread_1_68194692:thread_1_67597227:thread_1_90845214:thread_1_67585311:thread_1_91659557:reader_3_378758:thread_1_69837308");
        hashMap.put("100000121", "reader_3_379452:thread_1_91459757:thread_1_91404710:reader_3_379413:thread_1_91465713:thread_1_91119369:reader_3_375384:thread_1_91712612:adv_72_57489:thread_1_91629896:thread_1_91426566:thread_1_90612476:thread_1_68572318:thread_1_90863413:thread_1_49751351:thread_1_91926304:thread_1_90569832:thread_1_90414851:thread_1_90474743:thread_1_91561198:thread_1_91371876:thread_1_90780247:thread_1_91200134:thread_1_91979020:thread_1_91270058:thread_1_90610023:thread_1_91392131:thread_1_91522952:thread_1_91956986:thread_1_90723207:thread_1_68420360:thread_1_91150398:thread_1_90423218:thread_1_89721343:thread_1_91937463:ask_1_24067524:ask_1_24077215:reader_3_370966:reader_3_367804:ask_1_24076845:reader_3_382484:reader_3_367751:reader_3_366710:reader_3_374545:reader_3_379392:reader_3_371370:thread_1_90792183:thread_1_36555206:thread_1_91385679:thread_1_50152548:ask_1_24085556:thread_1_56396001:thread_1_91580199:thread_1_40802498:thread_1_68596492:thread_1_69180098:thread_1_45573679:thread_1_42374275:thread_1_41116020:thread_1_92036427:thread_1_68995022:thread_1_91941005:reader_3_381940:thread_1_67635991:thread_1_42088244:thread_1_90720877:thread_1_91941021:reader_3_375845:thread_1_90967572:thread_1_91488327:thread_1_90937088:reader_3_369096:ask_1_24080327:thread_1_54483938:reader_3_380489:thread_1_69196631:thread_1_91522046:thread_1_92206148:thread_1_91988614:thread_1_42886788:reader_3_372053:thread_1_91403278:thread_1_53268196:thread_1_90760112:thread_1_91033472:thread_1_92072604:thread_1_92104080:thread_1_91355075:thread_1_45077585:thread_1_91668902:reader_3_379687:thread_1_91075625:thread_1_91472239:thread_1_92034836:thread_1_57068339:thread_1_90643380:thread_1_90800416:thread_1_91529572:thread_1_91259131:thread_1_90951702:ask_1_24086931:thread_1_90762487:reader_3_383089:reader_3_375917:thread_1_68902442:thread_1_90925631:thread_1_91680251:adv_7_85385:thread_1_90967101:thread_1_90351205:thread_1_90718567:thread_1_91187685:thread_1_91005966:thread_1_91431666:thread_1_90978376:thread_1_91036448:ask_1_18076913:ask_1_23411923:thread_1_46587245:thread_1_92271518:thread_1_92102448:adv_0_72404:thread_1_50766252:reader_3_381554:class_1_16854:reader_3_371446:thread_1_90289671:reader_3_366764:adv_17_71690:reader_3_368587:thread_1_90892054:thread_1_90909715:thread_1_91269410:thread_1_67626316:thread_1_69542867:thread_1_91058318:reader_3_372680:thread_1_68222979:thread_1_69825243:thread_1_91212334:thread_1_90703371:thread_1_90795100:thread_1_90654412:thread_1_91341594:thread_1_91674383:thread_1_69416659:thread_1_90422584:thread_1_90459729:reader_3_369101:thread_1_91425069:thread_1_90874019:thread_1_91465838:reader_3_375205:thread_1_91298807:thread_1_91608857:thread_1_91738760:thread_1_91170394:ask_1_24074634:thread_1_69489129:thread_1_90682011:ask_1_24080264:thread_1_91500528:ask_1_24074451:thread_1_91926432:thread_1_91713660:thread_1_67638464:ask_1_24072393:thread_1_90654697:thread_1_91953881:thread_1_90892056:thread_1_91383842:thread_1_68608523:thread_1_90975963:thread_1_91489770:thread_1_91347418:thread_1_90632869:thread_1_91475335:thread_1_91632416:thread_1_91212583:thread_1_91537080:thread_1_91452898:thread_1_90660378:thread_1_91931198:reader_3_369697:thread_1_90743434:thread_1_91212444:thread_1_54836361:thread_1_91328804:reader_3_365118:thread_1_91016851:thread_1_80920786:thread_1_91350898:thread_1_69837213:thread_1_68739293:thread_1_91430726:thread_1_90685133:thread_1_91456694:thread_1_91264025:thread_1_90758955:thread_1_91140166:thread_1_91425137:thread_1_91305009:thread_1_91480189:thread_1_89954224:class_1_9383:thread_1_57174532:thread_1_91290114:thread_1_91242618:thread_1_90888058:thread_1_86055381:thread_1_90384014:thread_1_69888401:thread_1_90723261:reader_3_367962:thread_1_91637855:thread_1_91376198:thread_1_91465995:thread_1_68547035:thread_1_68771046:thread_1_91573632:thread_1_90931039:thread_1_91230206:reader_3_369749:thread_1_68713009:thread_1_69653077:thread_1_91747318:thread_1_92325531:reader_3_381305:reader_3_374148:reader_3_373830:ask_1_24088434:reader_3_376199:reader_3_402539:ask_1_17698922:ask_1_19310036:ask_1_16945883:reader_3_391124:reader_3_382793:reader_3_384233:reader_3_384517:ask_1_24080453:thread_1_91697475:reader_3_374024:thread_1_69402892:thread_1_91657240:thread_1_91495288:thread_1_91658468:thread_1_91561673:thread_1_91016850:thread_1_91433239:thread_1_49609924:thread_1_91177923:thread_1_91342637:thread_1_91673953:thread_1_91502338:thread_1_91092698:thread_1_68014939:thread_1_90568298:thread_1_69595900:thread_1_91046932:thread_1_90684509:thread_1_90876300:reader_3_377223:class_1_10695:reader_3_379701:thread_1_92167910:thread_1_92163599:thread_1_91698199:thread_1_44324331:thread_1_68887293:thread_1_69486016:thread_1_92010705:thread_1_91947697:thread_1_90805361:thread_1_91118659:thread_1_91618606:thread_1_92130353:reader_3_382471:thread_1_91709586:thread_1_91709381:thread_1_91509685:thread_1_69027980:thread_1_92153476:thread_1_69294127:thread_1_92137717:thread_1_69355300:thread_1_68253055:thread_1_69790684:reader_3_382020:thread_1_89947671:thread_1_92023609:thread_1_69502190:thread_1_91465391:thread_1_91014226:thread_1_69736414:thread_1_68870971:thread_1_69591170:reader_3_376205:thread_1_91286488:thread_1_90909632:thread_1_91580633:thread_1_91196975:thread_1_90940286:thread_1_90931499:thread_1_67509272:thread_1_90691026:thread_1_90636672:thread_1_92245131:ask_1_24088700:thread_1_68367590:thread_1_91974108:thread_1_92353555:reader_3_375324:ask_1_19704085:thread_1_91210638:thread_1_92288068:thread_1_92059636:thread_1_91148297:thread_1_92422713:thread_1_91638340:thread_1_92432466:thread_1_91164491:thread_1_92314422:thread_1_68677289:reader_3_382906:thread_1_91413939:thread_1_92547875:reader_3_403083:thread_1_90703366:reader_3_383446:thread_1_69461682:thread_1_91348380:thread_1_91532003:thread_1_91585996:thread_1_91935959:thread_1_91302010:thread_1_91572156:thread_1_92110560:thread_1_92365533:thread_1_91741421:thread_1_90761028:thread_1_92142761:thread_1_68015347:thread_1_68380484:thread_1_50619776:thread_1_90788834:thread_1_75949438:thread_1_37680413:thread_1_48839960:thread_1_47089356:ask_1_24088083:thread_1_47824879:thread_1_49189074:thread_1_47848307:thread_1_68712080:thread_1_49999191:thread_1_92142906:thread_1_49821967:thread_1_89049547:thread_1_47979566:thread_1_48578099:thread_1_69009433:thread_1_68831774:thread_1_68424366:thread_1_69668205:thread_1_92333657:thread_1_50076656:thread_1_92135954:thread_1_68986866:thread_1_47808736:thread_1_92143275:thread_1_69640902:thread_1_77246982:thread_1_68746516:thread_1_92485171:thread_1_91399057:thread_1_92463970:thread_1_92177257:thread_1_92422712:ask_1_19559598:thread_1_91428677:thread_1_90889957:thread_1_90566271:thread_1_90564931:thread_1_91405462:thread_1_89948535:thread_1_90362377:thread_1_68175445:thread_1_67644425:thread_1_90854302:thread_1_90355926:thread_1_69189638:thread_1_89704154:thread_1_69828799:thread_1_91199093:thread_1_91605184:thread_1_89667080:thread_1_91242764:thread_1_91431463:thread_1_91554202:thread_1_90520046:thread_1_91466607:thread_1_90570425:thread_1_91510374:reader_3_372534:thread_1_69028835:thread_1_91440105:thread_1_68705890:thread_1_69458304:thread_1_69288313:thread_1_68768886:thread_1_91208478:thread_1_69636944:thread_1_90412694:reader_3_361468:thread_1_89025849:thread_1_68701660:thread_1_68417953:thread_1_68223009:thread_1_90903514:thread_1_68506218:thread_1_90967399:thread_1_91232374:thread_1_69671083:thread_1_91047991:thread_1_90403515:thread_1_91286058:thread_1_90875944:thread_1_68215611:thread_1_69429485:thread_1_68240746:thread_1_90908007:thread_1_48964832:reader_3_380813:thread_1_92514493:thread_1_69829892:reader_3_383884:thread_1_92285856:ask_1_17103446:ask_1_24098311:thread_1_51984635:ask_1_18161309:reader_3_385683:thread_1_92622888:reader_3_388369:reader_3_385539:reader_3_383800:reader_3_377748:reader_3_376465:reader_3_377693:mamagoods_1_3046:adv_7_82280:thread_1_67449672:reader_3_375016:thread_1_91398013:reader_3_368345:ask_1_24082671:thread_1_90950274:thread_1_68067330:thread_1_91971052:thread_1_91466604:thread_1_91498042:thread_1_43571013:thread_1_43782031:thread_1_90365896:thread_1_69610811:thread_1_41897391:thread_1_90436967:thread_1_43418317:thread_1_92001255:thread_1_69283566:thread_1_38498181:thread_1_69470117:thread_1_69418787:thread_1_91204177:thread_1_91256281:thread_1_91010282:thread_1_91301036:thread_1_91371799:thread_1_91378351:thread_1_91180223:thread_1_90951606:thread_1_41791434:thread_1_91572168:thread_1_90922251:ask_1_20882215:reader_3_383109:ask_2_18940559:thread_1_68338263:thread_1_91206058:ask_1_24083546:thread_1_90838305:thread_1_91168195:thread_1_91317515:thread_1_90939778:thread_1_90804493:thread_1_90921168:thread_1_90943261:thread_1_90973267:thread_1_90996364:thread_1_90616332:reader_3_373774:thread_1_90709276:thread_1_89951765:reader_3_374498:ask_1_24068795:thread_1_91080412:ask_1_24080298:thread_1_37038398:thread_1_69035140:thread_1_90651066:thread_1_69679417:thread_1_68599141:thread_1_68481196:thread_1_91420434:thread_1_69360155:thread_1_89649498:thread_1_68688196:thread_1_91446108:thread_1_68671888:thread_1_68194692:thread_1_67597227:thread_1_90845214:thread_1_67585311:thread_1_91659557:reader_3_378758:thread_1_69837308");
        hashMap.put("100000122","");
        //测试基本组件批量插入
        rest.batchPutInPipelined(hashMap);
        rest.batchQueryByKeys(new ArrayList<>(hashMap.keySet()))
                .forEach((k, v) -> System.out.println(k+":"+v));
        rest.setDeleteKey(hashMap.keySet(),0);
        //布隆过滤器批量插入数据
        rest.addBatch(hashMap);
        rest.containsBatch(hashMap);
        System.out.println(rest.contains("thread_1_67597227","100000121","v"));
        //生成1000个数值测试误差率是否0.001
        Map<String,String> randomValueMap =  new HashMap<>();
        String result = IntStream.range(0, 1000)
                .mapToObj(String::valueOf)
                .map(s -> "thread_1_" + s)
                        .reduce((s1,s2) -> s1.concat(":").concat(s2)).get();
        System.out.println(result);
        randomValueMap.put("100000121",result);
        rest.containsBatch(randomValueMap);
        //uuid 生成测试误差率
        for (int i = 0; i < 1000000; i++) {
            String uuid = UUID.randomUUID().toString();
            Boolean re = rest.contains(uuid, "100000122", "v");
            if (re) System.out.println("出现误差值："+uuid);
        }
        REST.getInstance().destroy();
    }
}
