/**
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Authors: Konrad Iwanicki (iwanicki@mimuw.edu.pl)
 *          Dominik Wawszczak (dw440014@students.mimuw.edu.pl)
 */

package cp2022.solution;

import java.util.Collection;

import cp2022.base.Workplace;
import cp2022.base.Workshop;

public final class WorkshopFactory {
	public final static Workshop newWorkshop(Collection<Workplace> workplaces) {
		return new MyWorkshop(workplaces);
	}
}
