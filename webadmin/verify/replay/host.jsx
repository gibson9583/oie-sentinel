import React from 'react';
import { createRoot } from 'react-dom/client';
const state = new Map();
export const control = window.control = { permission: true, calls: [], pending: [], confirms: [], allowLeave: false, modal: null };
export const platform = {
    React, ReactDOM: { createRoot },
    checkTask: () => control.permission,
    ui: { h: () => document.createElement('span'), toast() {}, fmtNumber: String, fmtDate: String },
    store: { getState: (k) => state.get(k), setState: (k,v) => state.set(k,v), subscribe: () => () => {} },
    api: {
        get: async (path) => path.endsWith('/recovery') ? {rows:[{channelId:'00000000-0000-0000-0000-000000000001',metadataId:1,state:'PROBLEM',consecutiveBreachCount:0,healthyCount:1,required:3,openAlertEventId:19,problemStatus:'PROBLEM',lastEvaluatedTime:'2026-10-06T11:00:00Z'}],required:3,truncated:false} : [],
        post: (path, payload) => { control.calls.push({ path, payload }); return new Promise((resolve, reject) => control.pending.push({resolve,reject})); },
        put: (path, payload) => { control.calls.push({ path, payload }); return Promise.resolve(payload); },
        del: async () => ({}),
    },
};
export const confirmDialog = async (...args) => { control.confirms.push(args); return control.allowLeave; };
export const errorModal = (...args) => { control.modal = args; };
export const detailModal = (args) => { control.modal = args; };
export class DataTable { constructor() { this.el = document.createElement('div'); } setRows() {} }
export const promptDialog = async () => null;
export const fmtDate = String;
export const fmtNumber = String;
