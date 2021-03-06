package DominanceScoreUtils

import breeze.numerics.log2
import org.apache.spark.sql.functions.{col, collect_list, lit, log, size}
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.ListBuffer


object dominanceScore_2d_utils {

	/** Calculates dominance score for all points in the 1st given dataframe taking into considerations the dominated
	 *  points in the 2nd given dataframe
	 *
	 *  @param df1 1st given dataframe
	 *  @param df2 2nd given dataframe
	 *  @param sparkSession the spark session to call sql
	 *  @return a dataframe of points with their dominance score
	 */
	def calculate_dominance_score_2d(df1:DataFrame, df2:DataFrame, sparkSession: SparkSession, scoreToAdd:Int): DataFrame ={

		val joinDF = df1.as("df1")
			.join(df2.as("df2"), (col("df1.x") < col("df2.x") &&  col("df1.y") < col("df2.y")))
			.select(col("df1.id").alias("lid"),
				col("df2.id").alias("rid"))


		val scored_df_without_zero = joinDF.groupBy(col("lid").alias("id"))
			.agg(size(collect_list("rid")).alias("counted_score"))
			.withColumn("score", lit(scoreToAdd) + col("counted_score"))
			.select(col("id"), col("score"))

		val scored_df = df1.select("id").join(scored_df_without_zero, Seq("id"), "full_outer").na.fill(scoreToAdd, Seq("score"))

		scored_df
	}


	/** Creates the axis need for grid using the mean
	 *
	 *  @param axis_mean mean of the dataframe
	 *  @param size size of the axis (4, 8, 16)
	 *  @return a list with axis' lines
	 */
	def create_grid_axis(axis_mean:Double, size:Int): List[Double] ={

		var first_point = axis_mean
		val limit = (log2(size) - 1).toInt

		for(_ <- 0 until limit){
				first_point = first_point / 2.0
		}

		var axis_list = new ListBuffer[Double]
		axis_list.append(first_point)
		var current_point = first_point
		for(_  <- 0 until size - 2){
			current_point += first_point
			axis_list.append(current_point)
		}

		axis_list.toList

	}

	/** Creates the grid cells to check with pruning
	 *
	 *  @param df given dataframe size of x axis
	 *  @param x_max max point of x axis
	 *  @param y_max max point of y axis
	 *  @param x_axis list with x axis lines
	 *  @param y_axis list with x axis lines
	 *  @param k top k dominant points
	 *  @return a list with cells that must be checked
	 */
	def create_grid_cells_to_check_2d(
																		df: DataFrame,
																		x_max:Double,
																		y_max:Double,
																		x_axis:List[Double],
																		y_axis:List[Double],
																		k:Int): ListBuffer[((Int, Int), (Int, Double, Double, Double, Double), (Int, Int))] ={

		val x_axis_size:Int = x_axis.size
		val y_axis_size:Int = y_axis.size
		var grid_cells_with_counts = new ListBuffer[((Int, Int), (Int, Double, Double, Double, Double))]
		for(i <- 0 to x_axis_size){
			for(j <- 0 to y_axis_size){
				val grid_cell = (i, j)
				val borders = get_cell_borders(
					x_max,
					y_max,
					grid_cell,
					x_axis,
					y_axis)

				val x_line_left = borders(0)
				val x_line_right = borders(1)
				val y_line_up = borders(2)
				val y_line_down = borders(3)

				val number_of_points_in_cell = df.filter("x <= " + x_line_right + " AND y <= " + y_line_up +
					" AND " + " x > " + x_line_left + " AND  y > " + y_line_down).count().toInt

//
				grid_cells_with_counts.append(
					(grid_cell, (number_of_points_in_cell, x_line_left, x_line_right, y_line_up, y_line_down)))

			}
		}

		val grid_cells_with_counts_map = grid_cells_with_counts.toMap

		var candidate_grid_cells = new ListBuffer[((Int, Int), (Int, Double, Double, Double, Double))]
		for(grid_cell <- grid_cells_with_counts_map){
			var number_of_dominating_points = 0

			for(cell <- grid_cells_with_counts){
				if (cell._1._1 < grid_cell._1._1 && cell._1._2 < grid_cell._1._2)
					number_of_dominating_points += cell._2._1
			}

			if(number_of_dominating_points < k && grid_cell._2._1 > 0)
				candidate_grid_cells.append(grid_cell)
		}

		var candidate_grid_cells_with_bound_scores =  new ListBuffer[((Int, Int), (Int, Double, Double, Double, Double), (Int, Int))]
		for(grid_cell <- candidate_grid_cells){

			var partially_dominated_points_count = 0
			var fully_dominated_points = 0

			for(cell <- grid_cells_with_counts) {
				if (cell._1._1 == grid_cell._1._1 || cell._1._2 == grid_cell._1._2) {
					if (cell._1._1 >= grid_cell._1._1 && cell._1._2 >= grid_cell._1._2)
						partially_dominated_points_count += cell._2._1
				}
				else if(cell._1._1 > grid_cell._1._1 && cell._1._2 > grid_cell._1._2) {
						fully_dominated_points += cell._2._1
				}
			}

			partially_dominated_points_count -= grid_cell._2._1
			candidate_grid_cells_with_bound_scores.append(
				(grid_cell._1,
					grid_cell._2,
					(fully_dominated_points,
						fully_dominated_points + partially_dominated_points_count)))
		}

		var lower_bound_score = -1
		for( grid_cell <- candidate_grid_cells_with_bound_scores)
			if(grid_cell._2._1 >= k && grid_cell._3._1 > lower_bound_score)
				lower_bound_score = grid_cell._3._1


		var prunned_candidate_grid_cells = new ListBuffer[((Int, Int), (Int, Double, Double, Double, Double), (Int, Int))]
		for( grid_cell <- candidate_grid_cells_with_bound_scores)
			if(grid_cell._3._2 >= lower_bound_score)
				prunned_candidate_grid_cells.append(grid_cell)


		prunned_candidate_grid_cells
	}



	/** Get cell border lines for a given grid cell
	 *
	 *  @param x_max the max on x dimension of the dataframe, where the given cell is
	 *  @param y_max the max on y dimension of the dataframe, where the given cell is
	 *  @param grid_cell a given cell
	 *  @param x_axis a list with x axis lines
	 *  @param y_axis a list with y axis lines
	 *  @return a list with border lines
	 */
	def get_cell_borders(x_max:Double,
											 y_max:Double,
											 grid_cell:(Int, Int),
											 x_axis:List[Double],
											 y_axis:List[Double]): List[Double] ={

		var x_line_right = x_max
		if (grid_cell._1 != x_axis.size) {
			x_line_right =  x_axis(grid_cell._1)
		}
		var x_line_left = .0
		if (grid_cell._1 != 0){
			x_line_left = x_axis(grid_cell._1 - 1)
		}

		var y_line_up = y_max
		if (grid_cell._2 != y_axis.size) {
			y_line_up =  y_axis(grid_cell._2)
		}
		var y_line_down = .0
		if (grid_cell._2 != 0){
			y_line_down = y_axis(grid_cell._2 - 1)
		}

		List(x_line_left, x_line_right, y_line_up, y_line_down)
	}

}
