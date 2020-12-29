import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.{avg, col, collect_list, column, desc, expr, least, lit, max, min, rank, size, struct, udf, when}
import org.apache.spark.sql.expressions.Window
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.spark
import org.apache.spark.sql.types.{LongType, StructField, StructType}

import scala.collection.mutable.ListBuffer



 object bigdata {


   def Find_mins(total: DataFrame,rank_x: DataFrame,sparkSession: SparkSession) = {
     import sparkSession.implicits._

     val y_value = total.select(total("y").cast("double")).map(_.getDouble(0)).collect.toList
     val id = total.select(total("id").cast("Long")).map(_.getLong(0)).collect.toList
     var min_y = rank_x.select(max("y").cast("double")).first().getDouble(0)
     var tmin = min_y+1
     var skyline = new ListBuffer[Long]()

     for((y,idy)<-y_value zip id) if(y<tmin){tmin = y;skyline += idy}

     val values_sky = skyline.toList

     values_sky.toDF()

   }
   def task1(dataset_path :String): Unit = {

     Logger.getLogger("org").setLevel(Level.WARN)
     Logger.getLogger("akka").setLevel(Level.WARN)

     val conf = new SparkConf().setMaster("local[*]").setAppName("Skyline")
     val sparkSession = SparkSession.builder
       .config(conf = conf)
       .appName("Skyline")
       .getOrCreate()
     import sparkSession.implicits._



     val df = sparkSession.read.option("header", "true").csv(dataset_path )
       .select(col("0").alias("x"), col("1").alias("y"), col("id"))



     val sort_x = df.orderBy("x")
     val miny = sort_x.select(min("y")).first().getString(0)


     val RanksXY = sort_x.select("x","y","id")


     val minxy = RanksXY.select(RanksXY("x").cast("String")).where("y=="+miny).first().getString(0)

     val FilterXY = RanksXY.filter("x<="+minxy)


     val SkyXY = Find_mins(FilterXY,RanksXY,sparkSession)


     val skyline = SkyXY.select(col("value").alias("id"))
     print(skyline.show())



   }
   def main(args: Array[String]): Unit = {

     val dataset_path = args(0)
     val k=2

     val t1 = System.nanoTime
     task1(dataset_path )
     val duration = (System.nanoTime - t1)
     print(duration)


   }


 }
