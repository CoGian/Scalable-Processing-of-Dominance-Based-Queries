package SkylineDominanceScoreUtils

import DominanceScoreUtils.dominanceScore_2d_utils.get_cell_borders
import org.apache.spark.sql.DataFrame

import scala.collection.mutable.ListBuffer

object SkylineDominanceScore2d_utils {

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
																		 skyline: List[(Double, Double, Int)],
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

			var has_skyline_point = false
			for (point <- skyline){
				if (point._1 <= grid_cell._2._3 && point._1 <= grid_cell._2._4 && point._2 > grid_cell._2._2 && point._2 > grid_cell._2._5){
					has_skyline_point = true
				}
			}
			if(number_of_dominating_points < k && grid_cell._2._1 > 0 && has_skyline_point)
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

}
