package com.therepeator.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TheRepeatorRepository(
    private val dao: TheRepeatorRequestDao, 
    private val intruderDao: IntruderResultDao,
    private val browserDao: BrowserHistoryDao
) {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val history: Flow<List<HistoryItemSummary>> = dao.getAllRequestsSummary()

    companion object {
        private val sharedMatchReplaceRules = MutableStateFlow<List<MatchReplaceRule>>(emptyList())
        private val sharedVariables = MutableStateFlow<List<Variable>>(emptyList())
        private val sharedScopeRules = MutableStateFlow<List<ScopeRule>>(emptyList())
    }

    val matchReplaceRules: StateFlow<List<MatchReplaceRule>> = sharedMatchReplaceRules.asStateFlow()
    val variables: StateFlow<List<Variable>> = sharedVariables.asStateFlow()
    val scopeRules: StateFlow<List<ScopeRule>> = sharedScopeRules.asStateFlow()

    fun addMatchReplaceRule(rule: MatchReplaceRule) {
        sharedMatchReplaceRules.value = sharedMatchReplaceRules.value + rule
    }

    fun removeMatchReplaceRule(id: String) {
        sharedMatchReplaceRules.value = sharedMatchReplaceRules.value.filter { it.id != id }
    }

    fun toggleMatchReplaceRule(id: String) {
        sharedMatchReplaceRules.value = sharedMatchReplaceRules.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
    }

    fun addVariable(variable: Variable) {
        sharedVariables.value = sharedVariables.value + variable
    }

    fun removeVariable(id: String) {
        sharedVariables.value = sharedVariables.value.filter { it.id != id }
    }

    fun addScopeRule(rule: ScopeRule) {
        sharedScopeRules.value = sharedScopeRules.value + rule
    }

    fun removeScopeRule(id: String) {
        sharedScopeRules.value = sharedScopeRules.value.filter { it.id != id }
    }

    fun toggleScopeRule(id: String) {
        sharedScopeRules.value = sharedScopeRules.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
    }

    suspend fun addRequest(request: TheRepeatorRequest): Long {
        return dao.insertRequest(request)
    }

    suspend fun updateRequest(request: TheRepeatorRequest) {
        dao.updateRequest(request)
    }

    suspend fun clearHistory() {
        dao.deleteAll()
    }

    suspend fun deleteRequests(ids: List<Int>) {
        dao.deleteRequests(ids)
    }

    suspend fun getRequestById(id: Int): TheRepeatorRequest? {
        return dao.getRequestById(id)
    }

    fun getIntruderResults(attackId: String): Flow<List<IntruderResult>> {
        return intruderDao.getResultsForAttack(attackId)
    }

    suspend fun addIntruderResult(result: IntruderResult) {
        intruderDao.insertResult(result)
    }

    suspend fun addIntruderResults(results: List<IntruderResult>) {
        intruderDao.insertResults(results)
    }

    suspend fun clearIntruderResults(attackId: String) {
        intruderDao.deleteResultsForAttack(attackId)
    }

    val browserHistory: Flow<List<BrowserHistoryItem>> = browserDao.getAllHistory()
    suspend fun addBrowserHistory(item: BrowserHistoryItem) { browserDao.insert(item) }
    suspend fun clearBrowserHistory() { browserDao.deleteAll() }
}
